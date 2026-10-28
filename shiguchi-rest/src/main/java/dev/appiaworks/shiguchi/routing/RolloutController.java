package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.Status;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs the automated progressive rollouts ({@code services[].versions.rollout}).
 *
 * <p>Once a second, per service with a rollout enabled and its channel built: while one version
 * takes every call and a newer one is registered (and has not failed a rollout), it starts moving
 * calls to the newer one through the configured steps. Each step is judged on the new version's
 * own calls since the step began — the counters the version policy keeps in the service's
 * {@link TrafficControl}: once {@code min-calls} have ended, an error rate or unary p99 over the
 * limit rolls back at once; within the limits, the next step starts after {@code interval}. The
 * last step (100) passing completes the rollout, and the old version drains.
 *
 * <p>Every change goes through the same {@link TrafficControl} the {@code gateway-traffic}
 * endpoint uses, so a sticky caller only moves forward, and the endpoint shows each step. A change
 * made through the endpoint while a rollout runs pauses it; {@code resume} continues from the
 * current step.
 *
 * <p>State lives in this process: each gateway replica runs, judges and steps its own rollout on
 * the calls it routes, and a restarted gateway starts a still-pending rollout from the first step.
 */
@Component
public class RolloutController {

    private static final Logger log = LoggerFactory.getLogger(RolloutController.class);

    /** Where a service's rollout is. */
    public enum Phase {
        /** No rollout running: waiting for a newer version to register. */
        WATCHING,
        /** Moving calls to the candidate step by step. */
        ROLLING,
        /** Held at the current step, by an operator or a weight change through the endpoint. */
        PAUSED,
        /** The candidate failed a step; calls are back on the stable version. */
        ROLLED_BACK
    }

    private final GatewayProperties properties;
    private final GrpcChannelFactory channelFactory;
    private final Clock clock;
    private final boolean schedule;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private ScheduledExecutorService ticker;

    @Autowired
    public RolloutController(GatewayProperties properties, GrpcChannelFactory channelFactory) {
        this(properties, channelFactory, Clock.systemUTC(), true);
    }

    /** For tests: a given clock, and {@link #tick()} driven by the caller when {@code schedule} is false. */
    RolloutController(GatewayProperties properties, GrpcChannelFactory channelFactory, Clock clock, boolean schedule) {
        this.properties = properties;
        this.channelFactory = channelFactory;
        this.clock = clock;
        this.schedule = schedule;
    }

    @PostConstruct
    void start() {
        if (!schedule || rolloutServices().isEmpty()) return;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shiguchi-rollout");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::tickSafely, 1, 1, TimeUnit.SECONDS);
        log.info(
                "Automated rollouts on for {}",
                rolloutServices().stream().map(ServiceDefinition::getName).toList());
    }

    @PreDestroy
    void stop() {
        if (ticker != null) ticker.shutdownNow();
    }

    private List<ServiceDefinition> rolloutServices() {
        return properties.getServices().stream()
                .filter(def -> def.getVersions() != null
                        && def.getVersions().getRollout() != null
                        && def.getVersions().getRollout().isEnabled())
                .toList();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Rollout check failed: {}", e.toString(), e);
        }
    }

    /** One check of every service with a rollout. */
    synchronized void tick() {
        for (ServiceDefinition def : rolloutServices()) {
            TrafficControl control =
                    channelFactory.trafficControl(def.getName()).orElse(null);
            if (control == null) continue; // channel not built yet
            State state = states.computeIfAbsent(def.getName(), n -> new State());
            check(def.getVersions().getRollout(), control, state);
        }
    }

    private void check(ServiceDefinition.RolloutConfig config, TrafficControl control, State state) {
        Map<String, Integer> running = runningVersions(control);
        switch (state.phase) {
            case PAUSED -> {}
            case ROLLING -> {
                if (control.generation() != state.generation) {
                    state.phase = Phase.PAUSED;
                    decide(
                            state,
                            control,
                            "weights changed through the endpoint; paused at " + step(config, state)
                                    + " %, resume to continue");
                } else if (count(running, state.candidate) == 0) {
                    setShare(control, state, 0);
                    state.phase = Phase.WATCHING;
                    decide(
                            state,
                            control,
                            state.candidate + " is no longer registered; calls are back on " + state.stable);
                } else {
                    judge(config, control, state);
                }
            }
            case WATCHING, ROLLED_BACK -> maybeStart(config, control, state, running);
        }
    }

    /** Starts a rollout when one version takes every call and a newer, untried one runs. */
    private void maybeStart(
            ServiceDefinition.RolloutConfig config, TrafficControl control, State state, Map<String, Integer> running) {
        String stable = soleWeighted(control, running);
        if (stable == null) return;
        String candidate = null;
        for (String version : running.keySet()) {
            if (VersionOrder.INSTANCE.compare(version, stable) > 0 && !state.failed.contains(version)) {
                candidate = version; // running is oldest first: the last match is the newest
            }
        }
        if (candidate == null) return;
        state.stable = stable;
        state.candidate = candidate;
        state.stepIndex = 0;
        state.phase = Phase.ROLLING;
        enterStep(config, control, state);
        decide(
                state,
                control,
                "started rolling out " + candidate + " from " + stable + " at " + step(config, state) + " %");
    }

    /**
     * The version taking every call: the only one with a positive weight, or — without a split —
     * the oldest running one. Null while the split is shared, as during a manual rollout.
     */
    private static String soleWeighted(TrafficControl control, Map<String, Integer> running) {
        Map<String, Double> weights = control.currentWeights();
        if (weights.isEmpty()) {
            return running.isEmpty() ? null : running.keySet().iterator().next();
        }
        String sole = null;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            if (e.getValue() > 0) {
                if (sole != null) return null;
                sole = e.getKey();
            }
        }
        return sole;
    }

    private void judge(ServiceDefinition.RolloutConfig config, TrafficControl control, State state) {
        TrafficControl.Snapshot now = control.stats(state.candidate).snapshot();
        long ended = now.ended(state.window);
        long errors = now.ended(state.window, errorCodes(config));
        double rate = ended == 0 ? 0 : errors / (double) ended;
        double p99 = LatencyHistogram.quantileMillis(now.latencySince(state.window), 0.99);
        state.lastCalls = ended;
        state.lastErrors = errors;
        state.lastP99Millis = p99;
        if (ended < config.getMinCalls()) return; // too few calls to judge either way

        String breach = null;
        if (rate > config.getMaxErrorRate()) {
            breach = String.format(
                    Locale.ROOT,
                    "error rate %.2f %% over %d calls, limit %.2f %%",
                    rate * 100,
                    ended,
                    config.getMaxErrorRate() * 100);
        } else if (config.getMaxP99() != null
                && p99 >= 0
                && p99 > config.getMaxP99().toNanos() / 1e6) {
            breach = String.format(
                    Locale.ROOT,
                    "p99 %.1f ms over %d calls, limit %d ms",
                    p99,
                    ended,
                    config.getMaxP99().toMillis());
        }
        if (breach != null) {
            rollBack(
                    config,
                    control,
                    state,
                    "rolled back " + state.candidate + " at " + step(config, state) + " %: " + breach);
            return;
        }
        if (Duration.between(state.stepStartedAt, clock.instant()).compareTo(config.getInterval()) < 0) return;
        if (state.stepIndex == config.getSteps().size() - 1) {
            state.phase = Phase.WATCHING;
            decide(state, control, "promoted " + state.candidate + "; " + state.stable + " takes no calls and drains");
            return;
        }
        state.stepIndex++;
        enterStep(config, control, state);
        decide(state, control, state.candidate + " passed the step; now at " + step(config, state) + " %");
    }

    private void rollBack(ServiceDefinition.RolloutConfig config, TrafficControl control, State state, String why) {
        setShare(control, state, 0);
        state.failed.add(state.candidate);
        state.phase = Phase.ROLLED_BACK;
        decide(state, control, why);
        log.warn("Service '{}': {}", control.service(), why);
    }

    /** Applies the current step's share and opens its judging window. */
    private void enterStep(ServiceDefinition.RolloutConfig config, TrafficControl control, State state) {
        setShare(control, state, step(config, state));
        state.stepStartedAt = clock.instant();
        state.window = control.stats(state.candidate).snapshot();
        state.lastCalls = 0;
        state.lastErrors = 0;
        state.lastP99Millis = -1;
    }

    /** {@code share} percent to the candidate, the rest to the stable version, 0 to any other listed. */
    private static void setShare(TrafficControl control, State state, int share) {
        Map<String, Double> next = new LinkedHashMap<>();
        control.currentWeights().forEach((v, w) -> next.put(v, 0.0));
        put(next, state.stable, 100.0 - share);
        put(next, state.candidate, (double) share);
        control.apply(next, TrafficControl.Origin.ROLLOUT);
        state.generation = control.generation();
    }

    /** Sets {@code version}'s weight, replacing an entry naming the same version another way. */
    private static void put(Map<String, Double> weights, String version, double weight) {
        weights.keySet().removeIf(v -> VersionOrder.INSTANCE.compare(v, version) == 0);
        weights.put(version, weight);
    }

    private static int step(ServiceDefinition.RolloutConfig config, State state) {
        return config.getSteps().get(state.stepIndex);
    }

    private void decide(State state, TrafficControl control, String decision) {
        state.decision = decision;
        state.decidedAt = clock.instant();
        log.info("Service '{}': {}", control.service(), decision);
    }

    private static Map<String, Integer> runningVersions(TrafficControl control) {
        Map<String, Integer> running = new LinkedHashMap<>();
        control.running().forEach((version, n) -> {
            if (n > 0 && !version.isEmpty()) running.put(version, n);
        });
        return running;
    }

    private static int count(Map<String, Integer> running, String version) {
        for (Map.Entry<String, Integer> e : running.entrySet()) {
            if (VersionOrder.INSTANCE.compare(e.getKey(), version) == 0) return e.getValue();
        }
        return 0;
    }

    private static Set<Status.Code> errorCodes(ServiceDefinition.RolloutConfig config) {
        Set<Status.Code> codes = EnumSet.noneOf(Status.Code.class);
        if (config.getErrorCodes() != null) {
            config.getErrorCodes()
                    .forEach(c -> codes.add(Status.Code.valueOf(c.trim().toUpperCase(Locale.ROOT))));
        }
        return codes;
    }

    // ------------------------------------------------------------------ operator actions

    /**
     * Applies an operator action to {@code service}'s rollout: {@code pause}, {@code resume}
     * (continue a paused rollout from its step, or retry a rolled-back version from the first),
     * {@code abort} (roll back now) or {@code promote} (the candidate takes every call now).
     *
     * @throws IllegalArgumentException for an unknown action or a service without a rollout
     * @throws IllegalStateException when the action does not apply to where the rollout is
     */
    public synchronized void act(String service, String action) {
        ServiceDefinition def = rolloutServices().stream()
                .filter(d -> d.getName().equals(service))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Service '" + service + "' has no rollout: set versions.rollout.enabled"));
        TrafficControl control = channelFactory
                .trafficControl(service)
                .orElseThrow(() -> new IllegalStateException("Service '" + service + "' is not connected yet"));
        ServiceDefinition.RolloutConfig config = def.getVersions().getRollout();
        State state = states.computeIfAbsent(service, n -> new State());
        switch (action.toLowerCase(Locale.ROOT)) {
            case "pause" -> {
                require(state, Phase.ROLLING, action);
                state.phase = Phase.PAUSED;
                decide(state, control, "paused by an operator at " + step(config, state) + " %");
            }
            case "resume" -> {
                if (state.phase == Phase.PAUSED) {
                    state.phase = Phase.ROLLING;
                    enterStep(config, control, state);
                    decide(state, control, "resumed by an operator at " + step(config, state) + " %");
                } else if (state.phase == Phase.ROLLED_BACK) {
                    state.failed.remove(state.candidate);
                    state.phase = Phase.WATCHING;
                    decide(state, control, state.candidate + " may be rolled out again; starting over");
                    check(config, control, state);
                } else {
                    throw new IllegalStateException("nothing to resume: the rollout is " + state.phase);
                }
            }
            case "abort" -> {
                if (state.phase != Phase.ROLLING && state.phase != Phase.PAUSED) {
                    throw new IllegalStateException("no rollout to abort: it is " + state.phase);
                }
                rollBack(config, control, state, "aborted by an operator; " + state.candidate + " rolled back");
            }
            case "promote" -> {
                if (state.phase != Phase.ROLLING && state.phase != Phase.PAUSED) {
                    throw new IllegalStateException("no rollout to promote: it is " + state.phase);
                }
                setShare(control, state, 100);
                state.phase = Phase.WATCHING;
                decide(state, control, "promoted " + state.candidate + " by an operator");
            }
            default ->
                throw new IllegalArgumentException(
                        "unknown rollout action '" + action + "': use pause, resume, abort or promote");
        }
    }

    private static void require(State state, Phase phase, String action) {
        if (state.phase != phase)
            throw new IllegalStateException("cannot " + action + ": the rollout is " + state.phase);
    }

    /** Where {@code service}'s rollout is, for the {@code gateway-traffic} endpoint; null without one. */
    public synchronized Map<String, Object> status(String service) {
        ServiceDefinition def = rolloutServices().stream()
                .filter(d -> d.getName().equals(service))
                .findFirst()
                .orElse(null);
        if (def == null) return null;
        ServiceDefinition.RolloutConfig config = def.getVersions().getRollout();
        State state = states.getOrDefault(service, new State());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", state.phase.name());
        if (state.stable != null) out.put("stable", state.stable);
        if (state.candidate != null) out.put("candidate", state.candidate);
        if (state.phase == Phase.ROLLING || state.phase == Phase.PAUSED) {
            out.put("step", step(config, state));
            out.put("steps", config.getSteps());
            out.put("stepStartedAt", state.stepStartedAt.toString());
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("calls", state.lastCalls);
            window.put("errors", state.lastErrors);
            window.put("errorRate", state.lastCalls == 0 ? 0.0 : state.lastErrors / (double) state.lastCalls);
            if (state.lastP99Millis >= 0) window.put("p99Millis", state.lastP99Millis);
            window.put("minCalls", config.getMinCalls());
            out.put("window", window);
        }
        if (!state.failed.isEmpty()) out.put("failedVersions", List.copyOf(state.failed));
        if (state.decision != null) {
            out.put("lastDecision", state.decision);
            out.put("decidedAt", state.decidedAt.toString());
        }
        return out;
    }

    /** One service's rollout. Read and written under the controller's lock. */
    private static final class State {
        Phase phase = Phase.WATCHING;
        String stable;
        String candidate;
        int stepIndex;
        Instant stepStartedAt;
        TrafficControl.Snapshot window = TrafficControl.Snapshot.EMPTY;
        long generation = -1;
        long lastCalls;
        long lastErrors;
        double lastP99Millis = -1;
        final Set<String> failed = new TreeSet<>(VersionOrder.INSTANCE);
        String decision;
        Instant decidedAt;
    }
}
