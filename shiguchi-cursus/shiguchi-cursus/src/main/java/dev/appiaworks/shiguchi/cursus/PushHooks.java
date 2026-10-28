package dev.appiaworks.shiguchi.cursus;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessage;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult;
import dev.appiaworks.shiguchi.proto.cursus.ConnectDecision;
import dev.appiaworks.shiguchi.proto.cursus.ConnectionInfo;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeHook;
import dev.appiaworks.shiguchi.proto.cursus.TopicActivity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fans the push surface's events out to the {@link PushHook}s: topic activity (first local
 * subscriber, last one gone after {@code hooks.idle-linger}, so a reconnect does not flap) and
 * connection open and close. Notifications are fire-and-forget with {@code hooks.timeout}; a hook
 * that fails is logged and counted. The decisions (connect, subscribe, client message) are awaited
 * with the same timeout: the first reject wins, rewrites compose in bean order, and a hook that
 * times out or fails allows unless {@code hooks.fail-closed}. With no hook registered, nothing is
 * tracked and every decision is an allow.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class PushHooks implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PushHooks.class);

    private final PushProperties properties;
    private final List<PushHook> hooks;
    private final PushMetrics metrics;
    private final String instance;
    private final ConcurrentHashMap<Topic, Activity> active = new ConcurrentHashMap<>();

    private static final class Activity {
        int subscribers;
        Disposable linger;
    }

    public PushHooks(PushProperties properties, List<PushHook> hooks, PushMetrics metrics, String instance) {
        this.properties = properties;
        this.hooks = List.copyOf(hooks);
        this.metrics = metrics;
        this.instance = instance;
    }

    /** How many hooks listen. */
    public int size() {
        return hooks.size();
    }

    /** The hooks' class names, for the overview. */
    public List<String> names() {
        return hooks.stream().map(h -> h.getClass().getSimpleName()).toList();
    }

    /** Topics with a local subscriber (or inside their idle linger) as the hooks saw them. */
    public int activeTopics() {
        return active.size();
    }

    /** This instance's name in the calls. */
    public String instance() {
        return instance;
    }

    /** {@code live} with the topic's activity tracked: the first subscriber fires active, the last one idle after the linger. */
    Flux<PushEvent> track(Topic topic, Flux<PushEvent> live) {
        if (hooks.isEmpty()) return live;
        return live.doOnSubscribe(s -> up(topic)).doFinally(s -> down(topic));
    }

    private void up(Topic topic) {
        boolean first;
        synchronized (active) {
            Activity a = active.computeIfAbsent(topic, t -> new Activity());
            a.subscribers++;
            if (a.linger != null) {
                a.linger.dispose();
                a.linger = null;
                first = false;
            } else {
                first = a.subscribers == 1;
            }
        }
        if (first) fire("topic_active", h -> h.onTopicActive(activity(topic, 1)));
    }

    private void down(Topic topic) {
        synchronized (active) {
            Activity a = active.get(topic);
            if (a == null) return;
            a.subscribers--;
            if (a.subscribers > 0) return;
            Duration linger = properties.getHooks().getIdleLinger();
            if (linger == null || linger.isZero() || linger.isNegative()) {
                active.remove(topic);
                fire("topic_idle", h -> h.onTopicIdle(activity(topic, 0)));
                return;
            }
            a.linger = Mono.delay(linger, Schedulers.parallel()).subscribe(t -> {
                boolean idle;
                synchronized (active) {
                    idle = a.subscribers == 0 && active.remove(topic, a);
                }
                if (idle) fire("topic_idle", h -> h.onTopicIdle(activity(topic, 0)));
            });
        }
    }

    /** A connection opened. */
    void connected(PushConnections.Live live) {
        if (hooks.isEmpty()) return;
        fire("connect", h -> h.onConnect(connection(live, "")));
    }

    /** A connection closed, for {@code reason}. */
    void disconnected(PushConnections.Live live, String reason) {
        if (hooks.isEmpty()) return;
        fire("disconnect", h -> h.onDisconnect(connection(live, reason == null ? "closed" : reason)));
    }

    // ---- decisions ----

    /**
     * The connect decision of {@code live}: the topics to subscribe it to on the server side
     * (possibly none), or {@link PushException#forbidden} when a hook rejects it.
     */
    Mono<List<Topic>> admit(PushConnections.Live live) {
        if (hooks.isEmpty()) return Mono.just(List.of());
        return Mono.defer(() -> {
            ConnectionInfo info = connection(live, "");
            return Flux.fromIterable(hooks)
                    .concatMap(h -> ask("decide_connect", h, x -> x.decideConnect(info)))
                    .takeUntil(ConnectDecision::getReject)
                    .collectList()
                    .flatMap(decisions -> {
                        List<Topic> subscribe = new ArrayList<>();
                        for (ConnectDecision d : decisions) {
                            if (d.getReject()) return Mono.<List<Topic>>error(rejected(d.getReason()));
                            for (String raw : d.getSubscribeList()) {
                                Topic t = hookTopic(raw);
                                if (!subscribe.contains(t)) subscribe.add(t);
                            }
                        }
                        return Mono.just(subscribe);
                    });
        });
    }

    /** {@code topics} as the subscribe decision leaves them: rewritten, or forbidden when a hook rejects. */
    Mono<List<Topic>> subscribing(PushConnections.Live live, List<Topic> topics) {
        if (hooks.isEmpty()) return Mono.just(topics);
        return Mono.defer(() -> {
            ConnectionInfo info = connection(live, "");
            Mono<List<Topic>> chain = Mono.just(topics);
            for (PushHook hook : hooks) {
                chain = chain.flatMap(current -> {
                    SubscribeHook request = SubscribeHook.newBuilder()
                            .setConnection(info)
                            .addAllTopics(current.stream().map(Topic::toString).toList())
                            .build();
                    return ask("decide_subscribe", hook, x -> x.decideSubscribe(request))
                            .flatMap(d -> {
                                if (d.getReject()) return Mono.<List<Topic>>error(rejected(d.getReason()));
                                if (d.getTopicsCount() == 0) return Mono.just(current);
                                List<Topic> rewritten = new ArrayList<>();
                                for (String raw : d.getTopicsList()) {
                                    Topic t = hookTopic(raw);
                                    if (!rewritten.contains(t)) rewritten.add(t);
                                }
                                return Mono.just(rewritten);
                            })
                            .defaultIfEmpty(current);
                });
            }
            return chain;
        });
    }

    /**
     * The client message decision: the (possibly rewritten) result, {@code FORWARD} when no hook has
     * an opinion; a {@code DROP} or {@code REJECT} from one hook ends the chain.
     */
    Mono<ClientMessageResult> clientMessage(PushConnections.Live live, Topic topic, byte[] payload) {
        ClientMessageResult forward = ClientMessageResult.newBuilder()
                .setPayload(ByteString.copyFrom(payload))
                .build();
        if (hooks.isEmpty()) return Mono.just(forward);
        return Mono.defer(() -> {
            ConnectionInfo info = connection(live, "");
            Mono<ClientMessageResult> chain = Mono.just(forward);
            for (PushHook hook : hooks) {
                chain = chain.flatMap(current -> {
                    if (current.getAction() != ClientMessageResult.Action.FORWARD) return Mono.just(current);
                    ClientMessage request = ClientMessage.newBuilder()
                            .setConnection(info)
                            .setTopic(topic.toString())
                            .setPayload(current.getPayload())
                            .build();
                    return ask("client_message", hook, x -> x.onClientMessage(request))
                            .map(r -> r.getAction() == ClientMessageResult.Action.FORWARD
                                    ? current.toBuilder()
                                            .setPayload(
                                                    r.getPayload().isEmpty() ? current.getPayload() : r.getPayload())
                                            .build()
                                    : r)
                            .defaultIfEmpty(current);
                });
            }
            return chain;
        });
    }

    /** One decision call: counted, bounded by {@code hooks.timeout}; a timeout or failure allows (empty) unless fail-closed. */
    private <T> Mono<T> ask(String name, PushHook hook, Function<PushHook, Mono<T>> call) {
        PushProperties.Hooks conf = properties.getHooks();
        Duration timeout = conf.getTimeout();
        Mono<T> run = Mono.defer(() -> call.apply(hook));
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) run = run.timeout(timeout);
        return run.doOnSuccess(v -> metrics.hook(name, "ok")).onErrorResume(e -> {
            boolean timedOut = e instanceof java.util.concurrent.TimeoutException;
            metrics.hook(name, timedOut ? "timeout" : "error");
            log.warn(
                    "Push: hook {} of {} {}: {}",
                    name,
                    hook.getClass().getSimpleName(),
                    timedOut ? "timed out" : "failed",
                    e.toString());
            return conf.isFailClosed()
                    ? Mono.<T>error(PushException.forbidden("a hook could not be reached"))
                    : Mono.<T>empty();
        });
    }

    private Topic hookTopic(String raw) {
        Topic t;
        try {
            t = Topic.parse(raw);
        } catch (IllegalArgumentException e) {
            throw PushException.badRequest("a hook named the topic '" + raw + "': " + e.getMessage());
        }
        if (properties.namespaceOf(t) == null) throw PushException.unknownNamespace(t);
        return t;
    }

    private static PushException rejected(String reason) {
        return PushException.forbidden(reason == null || reason.isBlank() ? "rejected by a hook" : reason);
    }

    private TopicActivity activity(Topic topic, int subscribers) {
        return TopicActivity.newBuilder()
                .setTopic(topic.toString())
                .setInstance(instance)
                .setSubscribers(subscribers)
                .setTime(timestamp(Instant.now()))
                .build();
    }

    private ConnectionInfo connection(PushConnections.Live live, String reason) {
        PushConnections.Info info = live.info();
        ConnectionInfo.Builder b = ConnectionInfo.newBuilder()
                .setId(info.id())
                .setTransport(info.transport())
                .setInstance(instance)
                .setReason(reason)
                .addAllTopics(info.topics());
        if (info.principal() != null) b.setPrincipal(info.principal());
        if (info.since() != null) b.setSince(timestamp(info.since()));
        return b.build();
    }

    static Timestamp timestamp(Instant at) {
        return Timestamp.newBuilder()
                .setSeconds(at.getEpochSecond())
                .setNanos(at.getNano())
                .build();
    }

    private void fire(String name, Function<PushHook, Mono<Void>> call) {
        Duration timeout = properties.getHooks().getTimeout();
        for (PushHook hook : hooks) {
            Mono<Void> run;
            try {
                run = Mono.defer(() -> call.apply(hook));
            } catch (RuntimeException e) {
                failed(name, hook, e);
                continue;
            }
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) run = run.timeout(timeout);
            run.subscribe(v -> {}, e -> failed(name, hook, e), () -> metrics.hook(name, "ok"));
        }
    }

    private void failed(String name, PushHook hook, Throwable e) {
        metrics.hook(name, "error");
        log.warn("Push: hook {} of {} failed: {}", name, hook.getClass().getSimpleName(), e.toString());
    }

    @Override
    public void destroy() {
        synchronized (active) {
            for (Activity a : active.values()) if (a.linger != null) a.linger.dispose();
            active.clear();
        }
    }
}
