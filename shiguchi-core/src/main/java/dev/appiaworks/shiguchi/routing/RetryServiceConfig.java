package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a service's {@code retry} block into the gRPC service config the channel understands.
 *
 * <p>gRPC expects the config as the parsed form of the JSON in
 * <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md">gRFC A6</a>: a map
 * with a {@code methodConfig} list, each entry naming the methods it applies to and carrying a
 * {@code retryPolicy}. The gateway emits a wildcard entry (an empty {@code name} object, matching
 * every method on the channel) carrying the configured policy, preceded by one entry per method
 * that must not be retried. An entry with no {@code retryPolicy} means "never retry this method",
 * and a named entry wins over the wildcard — that pairing is what lets one channel-wide policy
 * still skip the methods that are unsafe to repeat.
 *
 * <p>Retrying in the channel (rather than around it) is what makes a retry useful: gRPC
 * re-resolves and re-balances each attempt, so a retry after {@code UNAVAILABLE} can land on a
 * different backend instance, and gRPC's retry throttling — the {@code retryThrottling} entry
 * emitted next to {@code methodConfig}, on unless {@code retry.throttling.enabled} is false —
 * stops retrying when a backend is failing broadly.
 *
 * <p>Which calls may be retried at all is decided per route, not here — see
 * {@link RouteDefinition#isRetryable()}. This config governs how a retryable call is retried.
 */
public final class RetryServiceConfig {

    private RetryServiceConfig() {}

    /**
     * Builds the service config map for a service, or {@code null} when retries are off — the
     * caller then leaves the channel's service config alone.
     *
     * @param excludedMethods fully-qualified {@code package.Service/Method} names that must not be
     *     retried, because repeating them is not known to be safe. Each gets its own
     *     {@code methodConfig} entry with no {@code retryPolicy}, which gRPC reads as "this method
     *     is never retried" and which takes precedence over the wildcard entry.
     */
    public static Map<String, Object> forService(ServiceDefinition service, Collection<String> excludedMethods) {
        var retry = service.getRetry();
        var hedging = service.getHedging();
        boolean retryOn = retry != null && retry.isEnabled();
        boolean hedgingOn = hedging != null && hedging.isEnabled();
        if (!retryOn && !hedgingOn) return null;

        // gRFC A6 allows a retryPolicy or a hedgingPolicy per method, never both; the validator
        // rejects a service that enables both, so retry winning here is only a guard.
        String policyKey;
        Map<String, Object> policy = new LinkedHashMap<>();
        ServiceDefinition.ThrottlingConfig throttling;
        if (retryOn) {
            policyKey = "retryPolicy";
            // gRPC requires a Double here, not an Integer, and rejects the config outright otherwise.
            policy.put("maxAttempts", (double) retry.getMaxAttempts());
            policy.put("initialBackoff", seconds(retry.getInitialBackoff()));
            policy.put("maxBackoff", seconds(retry.getMaxBackoff()));
            policy.put("backoffMultiplier", retry.getBackoffMultiplier());
            policy.put("retryableStatusCodes", codes(retry.getRetryableStatusCodes()));
            throttling = retry.getThrottling();
        } else {
            policyKey = "hedgingPolicy";
            policy.put("maxAttempts", (double) hedging.getMaxAttempts());
            policy.put("hedgingDelay", seconds(hedging.getHedgingDelay()));
            policy.put("nonFatalStatusCodes", codes(hedging.getNonFatalStatusCodes()));
            throttling = hedging.getThrottling();
        }

        List<Map<String, Object>> methodConfigs = new ArrayList<>();

        // Exclusions first. A method named here carries no policy, which gRPC reads as "never
        // retry or hedge this one", and a specific name beats the wildcard below.
        for (String method : new TreeSet<>(excludedMethods)) {
            int slash = method.lastIndexOf('/');
            if (slash <= 0 || slash == method.length() - 1) continue;
            Map<String, Object> excluded = new LinkedHashMap<>();
            excluded.put(
                    "name",
                    List.of(Map.of("service", method.substring(0, slash), "method", method.substring(slash + 1))));
            methodConfigs.add(excluded);
        }

        Map<String, Object> wildcard = new LinkedHashMap<>();
        // An empty name object matches every remaining method on the channel.
        wildcard.put("name", List.of(Map.of()));
        wildcard.put(policyKey, policy);
        methodConfigs.add(wildcard);

        Map<String, Object> serviceConfig = new LinkedHashMap<>();
        serviceConfig.put("methodConfig", List.copyOf(methodConfigs));

        // The retry budget sits beside methodConfig, channel-wide, and counts hedged attempts
        // too. Without it gRPC never throttles, and a broad outage turns every call into
        // max-attempts calls.
        if (throttling != null && throttling.isEnabled()) {
            Map<String, Object> retryThrottling = new LinkedHashMap<>();
            // Doubles again: gRPC's parser rejects an Integer maxTokens just as it does maxAttempts.
            retryThrottling.put("maxTokens", (double) throttling.getMaxTokens());
            retryThrottling.put("tokenRatio", throttling.getTokenRatio());
            serviceConfig.put("retryThrottling", retryThrottling);
        }
        return serviceConfig;
    }

    /**
     * The methods of a loaded service that must not be retried: everything the proto did not
     * declare safe to repeat, unless {@code retry-non-idempotent} says to retry regardless.
     */
    public static Set<String> nonRetryableMethods(ServiceDefinition service, Collection<RouteDefinition> routes) {
        var retry = service.getRetry();
        var hedging = service.getHedging();
        boolean guardRetry = retry != null && retry.isEnabled() && !retry.isRetryNonIdempotent();
        boolean guardHedging = hedging != null && hedging.isEnabled() && !hedging.isHedgeNonIdempotent();
        // Hedging repeats a call while the first attempt may still be running, so the same
        // idempotency rule applies to it as to retries.
        if (!guardRetry && !guardHedging) return Set.of();
        // A method can be reached by more than one route (additional_bindings, or a REST and a
        // GraphQL route over the same RPC). Retry is a property of the method, so it is allowed
        // only when every route onto it is safe to repeat: one unsafe binding excludes the method.
        Set<String> unsafe = new TreeSet<>();
        for (RouteDefinition route : routes) {
            if (route.getTransport() != RouteDefinition.Transport.GRPC) continue;
            if (!route.isRetryable()) {
                unsafe.add(route.getFullServiceName() + "/" + route.getMethodName());
            }
        }
        return unsafe;
    }

    /**
     * gRPC wants durations as a string of seconds with an {@code s} suffix, e.g. {@code "0.1s"}.
     */
    static String seconds(Duration duration) {
        long millis = Math.max(0, duration.toMillis());
        String value = String.format(Locale.ROOT, "%.3f", millis / 1000.0);
        // Trim trailing zeros so "1.000s" reads as "1s"; gRPC accepts both.
        if (value.contains(".")) {
            value = value.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return value + "s";
    }

    /**
     * Maps a proto method's {@code idempotency_level} option onto the gateway's own enum, so the
     * route loaders do not each repeat the translation.
     */
    public static RouteDefinition.IdempotencyLevel idempotencyOf(
            com.google.protobuf.DescriptorProtos.MethodOptions options) {
        if (options == null) return RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN;
        return switch (options.getIdempotencyLevel()) {
            case NO_SIDE_EFFECTS -> RouteDefinition.IdempotencyLevel.NO_SIDE_EFFECTS;
            case IDEMPOTENT -> RouteDefinition.IdempotencyLevel.IDEMPOTENT;
            default -> RouteDefinition.IdempotencyLevel.IDEMPOTENCY_UNKNOWN;
        };
    }

    /** Normalizes and validates the configured code names, rejecting OK and unknown names. */
    private static List<String> codes(List<String> configured) {
        List<String> names = new ArrayList<>();
        for (String raw : configured) {
            names.add(validate(raw).name());
        }
        return names;
    }

    /**
     * Parses one status-code name, with a message naming the offender — these come from YAML, so
     * a typo has to say what was wrong rather than surface as an opaque gRPC parse failure.
     */
    public static Status.Code validate(String name) {
        String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("status code must not be blank");
        }
        Status.Code code;
        try {
            code = Status.Code.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + name + "' is not a gRPC status code");
        }
        if (code == Status.Code.OK) {
            throw new IllegalArgumentException("OK is not a failure and cannot be listed");
        }
        return code;
    }
}
