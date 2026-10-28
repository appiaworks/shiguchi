package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One backend the gateway fronts: where it is, how its schema is discovered, and the per-service
 * overrides that apply to calls against it.
 *
 * <p>The address is given exactly one of three ways — {@code host} + {@code port} for a fixed
 * address, {@code discovery-name} to ask a Spring Cloud {@code DiscoveryClient}, or a raw gRPC
 * {@code target} such as {@code dns:///host:9090}. {@code target} wins over the others when
 * several are set, and the startup validator rejects an entry that gives none.
 *
 * <p>{@code mode} decides where the schema comes from: {@code DYNAMIC} asks the backend over
 * server reflection, {@code STATIC} reads compiled stubs off the gateway's classpath,
 * {@code PB_FILE} reads a {@code FileDescriptorSet}, and {@code REST_DOWNSTREAM} reads an
 * OpenAPI document from a backend that speaks HTTP rather than gRPC.
 *
 * <p>A definition is {@code insecure} by default. Since the caller's {@code Authorization}
 * header is forwarded to the backend, that means a bearer token is readable on this hop unless a
 * {@code tls} block is present — the gateway warns about exactly this at startup.
 */
@Data
@NoArgsConstructor
public class ServiceDefinition {
    private String name;
    private String host;
    private int port;

    /**
     * Reach the backend through Spring Cloud's {@code DiscoveryClient} (Eureka, Consul,
     * Kubernetes, ...) instead of {@code host}/{@code port}. The value is the registered
     * service id. The channel is built on {@code discovery:///<id>}: every registered
     * instance is an address, calls are balanced across them ({@code round_robin} unless
     * {@link #loadBalancingPolicy} says otherwise), and the list is re-read every
     * {@code client.discovery-refresh} and after a connection failure. An instance metadata
     * entry {@code grpc-port} overrides the registry port (for services that register their
     * HTTP port but serve gRPC elsewhere). Without a {@code DiscoveryClient} bean the name is
     * handed to any {@code GrpcServiceAddressResolver} bean instead.
     */
    private String discoveryName;

    /**
     * A raw gRPC channel target, taking precedence over {@code host}/{@code port} and
     * {@code discovery-name}: {@code dns:///orders.ns.svc.cluster.local:9090} balances across
     * every address a headless Kubernetes Service (or any multi-A DNS name) resolves to,
     * {@code xds:///orders} hands the choice to an xDS control plane. Whatever resolver gRPC
     * has for the scheme applies; {@code round_robin} unless {@link #loadBalancingPolicy} says
     * otherwise.
     */
    private String target;

    /**
     * gRPC load-balancing policy for the channel ({@code round_robin}, {@code pick_first},
     * or any policy registered with gRPC). Defaults to {@code round_robin} for
     * {@code discovery-name} and {@code target} channels and to gRPC's own default
     * ({@code pick_first}) for a static {@code host}/{@code port}.
     */
    private String loadBalancingPolicy;

    private GatewayMode mode = GatewayMode.DYNAMIC;
    private boolean insecure = true;

    /**
     * Per-service deadline for outbound unary calls; overrides
     * {@code shiguchi.client.default-deadline}. Null = inherit the global default;
     * {@code Duration.ZERO} = explicitly disable for this service.
     */
    private java.time.Duration deadline;

    /**
     * TLS settings for the channel to this backend. When set, the channel uses TLS with
     * these credentials and the {@code insecure} flag is ignored. Omit the block entirely
     * to use {@code insecure} (plaintext when true, system-default TLS when false).
     */
    private TlsConfig tls;

    /**
     * Automatic retries of failed calls to this backend, off by default. Translated into a
     * gRPC service config so retrying happens inside the channel, below the load balancer:
     * an attempt that fails on one instance can land on another.
     */
    private RetryConfig retry = new RetryConfig();

    /**
     * Hedged requests to this backend, off by default: send the call, and if no answer arrives
     * within {@code hedging-delay}, send it again to another instance, keeping whichever answers
     * first. Cuts tail latency at the cost of extra load. Mutually exclusive with {@code retry}
     * (gRPC service config allows one or the other per method).
     */
    private HedgingConfig hedging = new HedgingConfig();

    /**
     * Per-service circuit breaker, off by default. Trips after a run of failures and fails
     * calls fast with {@code UNAVAILABLE} until the backend proves healthy again.
     */
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

    /**
     * Channels (and so HTTP/2 connections per resolved address) opened to this backend, 1 by
     * default. Calls are spread across them round-robin. A single HTTP/2 connection caps the calls
     * in flight at the backend's {@code MAX_CONCURRENT_STREAMS} and runs on one event-loop thread;
     * behind a Kubernetes ClusterIP or any L4 balancer it also pins every call to one pod, since
     * the balancer chooses once per connection. Raise it for a busy backend reached through one
     * such address. Breaker state and {@link #maxConcurrentCalls} are shared by the whole pool.
     */
    private int channels = 1;

    /**
     * Largest number of calls to this backend in flight from this gateway instance at once; 0 (the
     * default) means no limit. A call over it fails at once with {@code UNAVAILABLE} (HTTP 503)
     * without reaching the backend, instead of queueing behind a backend that has stopped keeping
     * up. Counts logical calls: retries and hedges of one call count once. Rejections are not
     * failures to the circuit breaker, which sits below this limit.
     */
    private int maxConcurrentCalls = 0;

    /**
     * HTTP caching rules for this service's REST routes, first match wins. A rule overrides the
     * method's {@code (shiguchi.api.method).cache_ttl_seconds} proto option; a method no rule matches
     * keeps that option's TTL with the default policy.
     */
    private List<CacheRule> cache = new ArrayList<>();

    /**
     * One caching rule ({@code services[].cache[]}): which methods it covers and how their
     * responses may be cached, by clients and intermediaries ({@code Cache-Control}) and by the
     * gateway itself ({@code rest.response-cache}).
     */
    @Data
    @NoArgsConstructor
    public static class CacheRule {
        /**
         * Glob patterns of the methods this rule covers, like {@code include-methods}: matched
         * against {@code fully.qualified.Service/Method}, or the method name alone for a pattern
         * without {@code /}. Required.
         */
        private List<String> methods = new ArrayList<>();

        /**
         * How long a response stays fresh: {@code Cache-Control: max-age} and the response
         * cache's lifetime for the entry. {@code 0} makes the matched methods uncacheable, also
         * overriding a proto {@code cache_ttl_seconds}.
         */
        private java.time.Duration ttl = java.time.Duration.ZERO;

        /** {@code private} / {@code public}, and whether the response cache keys per caller. */
        private CacheVisibility visibility = CacheVisibility.AUTO;

        /** {@code s-maxage}: a different freshness lifetime for shared caches. Unset = none. */
        private java.time.Duration sharedMaxAge;

        /**
         * {@code stale-while-revalidate}: how long after {@code ttl} a stale response may still be
         * served while a fresh one is fetched in the background — by caches that honour the
         * directive, and by the response cache, which refreshes the entry with one backend call.
         * Unset = none.
         */
        private java.time.Duration staleWhileRevalidate;

        /**
         * Keep these responses in the gateway's response cache (null = inherit
         * {@code rest.response-cache.enabled}). {@code false} still sends the headers.
         */
        private Boolean serverCache;

        /** Whether this rule covers the method. */
        public boolean matches(String serviceFqn, String methodName) {
            String qualified = serviceFqn + "/" + methodName;
            for (String pattern : methods) {
                if (globMatches(pattern, qualified, methodName)) return true;
            }
            return false;
        }
    }

    /** The first {@link #cache} rule covering the method, or null. */
    public CacheRule cacheRuleFor(String serviceFqn, String methodName) {
        if (cache == null) return null;
        for (CacheRule rule : cache) {
            if (rule != null && rule.matches(serviceFqn, methodName)) return rule;
        }
        return null;
    }

    /**
     * Outlier detection across this backend's instances, off by default: an instance whose calls
     * fail far more often than its peers' is taken out of the load balancer's rotation for a while,
     * so one bad pod stops costing a share of every caller's requests. Only means something with
     * several addresses — a {@code discovery-name} or a {@code target} resolving to many.
     */
    private OutlierDetectionConfig outlierDetection = new OutlierDetectionConfig();

    /**
     * Outlier detection for one backend ({@code shiguchi.services[].outlier-detection}),
     * installed as gRPC's {@code outlier_detection_experimental} load-balancing policy around the
     * channel's own ({@code round_robin} unless {@code load-balancing-policy} says otherwise).
     *
     * <p>Every {@code interval} gRPC looks at each instance's calls since the last look and ejects
     * the outliers: an ejected instance gets no calls for {@code base-ejection-time} times the
     * number of times it has been ejected, capped at {@code max-ejection-time}. Never more than
     * {@code max-ejection-percent} of the instances are out at once, so a fleet-wide failure does
     * not eject everything.
     *
     * <p>Any status but {@code OK} counts as a failure — gRPC cannot tell a caller's
     * {@code NOT_FOUND} from a sick instance. That is why both algorithms compare an instance with
     * its peers rather than with an absolute bar: caller errors spread over every instance evenly.
     */
    @Data
    @NoArgsConstructor
    public static class OutlierDetectionConfig {
        private boolean enabled = false;

        /** How often instances are evaluated. */
        private java.time.Duration interval = java.time.Duration.ofSeconds(10);

        /** Ejection time for a first ejection; each further one multiplies it. */
        private java.time.Duration baseEjectionTime = java.time.Duration.ofSeconds(30);

        /** Longest an instance stays ejected, however often it was ejected before. */
        private java.time.Duration maxEjectionTime = java.time.Duration.ofMinutes(5);

        /** Largest share of the instances that may be ejected at once, 0 to 100. */
        private int maxEjectionPercent = 50;

        /**
         * Eject an instance whose failure rate over the interval is at least {@code threshold}
         * percent — the algorithm that works for small fleets. On by default within the block.
         */
        private FailurePercentageConfig failurePercentage = new FailurePercentageConfig();

        /**
         * Eject an instance whose success rate is more than {@code stdev-factor} standard deviations
         * below the fleet's mean. Statistically sound only with several instances; off by default.
         */
        private SuccessRateConfig successRate = new SuccessRateConfig();
    }

    @Data
    @NoArgsConstructor
    public static class FailurePercentageConfig {
        private boolean enabled = true;

        /** Failure percentage, 0 to 100, at or above which an instance is ejected. */
        private int threshold = 50;

        /** Instances that must have enough calls for the check to run at all. */
        private int minimumHosts = 2;

        /** Calls an instance needs in the interval to be judged. */
        private int requestVolume = 20;

        /** Chance, 0 to 100, that an instance found to be an outlier is actually ejected. */
        private int enforcementPercentage = 100;
    }

    @Data
    @NoArgsConstructor
    public static class SuccessRateConfig {
        private boolean enabled = false;

        /** How many standard deviations below the mean success rate counts as an outlier. */
        private double stdevFactor = 1.9;

        private int minimumHosts = 5;

        private int requestVolume = 100;

        private int enforcementPercentage = 100;
    }

    /**
     * How this backend's instances say which version they run, and which version the gateway
     * reads the schema from. Applies to {@code discovery-name} services, whose instances carry
     * registry metadata; see {@link VersionsConfig}.
     */
    private VersionsConfig versions = new VersionsConfig();

    /**
     * Versions of a {@code discovery-name} backend ({@code shiguchi.services[].versions}).
     *
     * <p>An instance publishes its version as registry metadata under {@code metadata-key}
     * (Eureka {@code metadata-map}, Consul {@code meta}, a Kubernetes pod label): typically set by
     * the deploy pipeline from the image tag, so the service itself needs no code.
     *
     * <p>During a rolling deploy two versions run side by side, with different schemas. The
     * gateway therefore never reads the schema through the load-balanced channel — successive
     * reflection calls, and successive refreshes, could each land on a different version — but
     * from one instance of the version {@code schema-from} picks, over a direct connection.
     */
    @Data
    @NoArgsConstructor
    public static class VersionsConfig {
        /** Instance metadata key holding the version. */
        private String metadataKey = "version";

        /**
         * Which version's schema the gateway serves: {@code OLDEST} (the default) exposes only what
         * every running instance can answer, so nothing new is routed until the old version is gone;
         * {@code NEWEST} exposes a new version's additions as soon as one of its instances is up,
         * for deploys that only add; any other value is an exact version, falling back to
         * {@code OLDEST} while no instance runs it. Versions compare numerically part by part
         * ({@code 1.10.0} after {@code 1.9.2}); an instance without the metadata sorts oldest.
         */
        private String schemaFrom = "OLDEST";

        /**
         * How calls are split between versions, e.g. {@code [{version: 1.4.0, weight: 90},
         * {version: 1.10.0, weight: 10}]}. Weights are relative. A version not listed gets no
         * calls, except as a fallback while no listed version has a ready instance, so a rollout
         * whose new version is not up yet keeps answering. Empty (the default) balances over every
         * instance as before. With a split, {@code schema-from: OLDEST} means the oldest version
         * that receives calls.
         */
        private List<TrafficSplit> traffic = new ArrayList<>();

        /**
         * Keep a caller on one version: the call metadata entry hashed to choose it, e.g.
         * {@code x-principal} (the authenticated principal) or a user-id header. Unset (the
         * default) picks per call. Callers whose value is missing or {@code anonymous} are picked
         * per call. Versions are laid out oldest first, so raising the newest version's weight
         * only ever moves callers onto it, never back. The header is forwarded to the backend.
         */
        private String stickyHeader;

        /**
         * A request header naming a version that call should go to, whatever the weights — to try
         * a new version before it takes traffic (weight 0). A version with no ready instance falls
         * back to the split. Unset (the default) disables it. The header is forwarded to the backend.
         */
        private String previewHeader;

        /**
         * Move calls onto a newly registered version step by step, and back when it misbehaves.
         * Off by default.
         */
        private RolloutConfig rollout = new RolloutConfig();

        /**
         * Whether calls are routed by version at all: a split, a preview header or an automated
         * rollout is configured.
         */
        public boolean routesByVersion() {
            return (traffic != null && !traffic.isEmpty())
                    || (previewHeader != null && !previewHeader.isBlank())
                    || (rollout != null && rollout.isEnabled());
        }
    }

    /**
     * An automated progressive rollout ({@code versions.rollout}).
     *
     * <p>While one version takes all the calls, a newer version registering starts a rollout: its
     * share goes through {@code steps}, each held for at least {@code interval} and judged on the
     * new version's own calls during it. A step passes once it has seen {@code min-calls} calls
     * with an error rate (calls ending in one of {@code error-codes}) at most
     * {@code max-error-rate} and, when set, a unary p99 at most {@code max-p99}; past the last
     * step the new version takes everything and the old one drains. A step that breaches either
     * limit — as soon as it has {@code min-calls} — puts every call back on the old version, and
     * that version is not tried again until a newer one appears or the rollout is resumed through
     * the {@code gateway-traffic} endpoint. Changing the weights through the endpoint pauses it.
     *
     * <p>Each gateway replica judges and steps on its own calls.
     */
    @Data
    @NoArgsConstructor
    public static class RolloutConfig {
        /** Run automated rollouts for this service. */
        private boolean enabled = false;

        /** The new version's share at each step, in percent: increasing, ending at 100. */
        private List<Integer> steps = new ArrayList<>(List.of(5, 25, 50, 100));

        /** How long each step lasts at least before it is judged and the next one starts. */
        private java.time.Duration interval = java.time.Duration.ofMinutes(2);

        /** Calls the new version must take in a step before the step is judged either way. */
        private long minCalls = 100;

        /** The highest share of the new version's calls in a step that may end in an error code. */
        private double maxErrorRate = 0.01;

        /** The highest p99 latency of the new version's unary calls in a step; unset to not judge it. */
        private java.time.Duration maxP99;

        /**
         * The gRPC status codes that count as errors — a server or deploy problem, not a caller's
         * mistake such as {@code NOT_FOUND} or {@code INVALID_ARGUMENT}. {@code UNIMPLEMENTED}
         * catches a new version missing a method the old one has.
         */
        private List<String> errorCodes = new ArrayList<>(
                List.of("UNKNOWN", "DEADLINE_EXCEEDED", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE", "DATA_LOSS"));
    }

    /** One version's share of the calls ({@code versions.traffic[]}). */
    @Data
    @NoArgsConstructor
    public static class TrafficSplit {
        private String version;

        /** Relative weight, 0 or more; 0 keeps the version running but sends it nothing. */
        private int weight;
    }

    @Data
    @NoArgsConstructor
    public static class TlsConfig {
        /**
         * Path to a PEM file with the CA certificate(s) used to verify the backend's
         * server certificate. Omit to trust the JVM's default trust store.
         */
        private String trustCertCollection;
        /** Path to this gateway's PEM client certificate chain (mTLS). Requires privateKey. */
        private String certChain;
        /** Path to the PKCS#8 PEM private key for certChain (mTLS). */
        private String privateKey;
    }

    /**
     * Retry policy for one backend ({@code shiguchi.services[].retry}).
     *
     * <p>Becomes a gRPC service config on the service's channel, so retrying happens inside
     * the channel rather than in the gateway: a retried attempt is re-resolved and
     * re-balanced, landing on a different instance when one is unhealthy, and gRPC's retry
     * throttling ({@code throttling}, on by default) stops a struggling backend from being
     * hammered.
     *
     * <p>Only calls that fail with one of {@code retryable-status-codes} are retried, and
     * only when they are safe to repeat — see {@code retry-non-idempotent}.
     */
    @Data
    @NoArgsConstructor
    public static class RetryConfig {
        /** Whether to retry failed calls to this backend at all. */
        private boolean enabled = false;

        /**
         * Total attempts, the first one included, so 3 means one call and two retries.
         * gRPC caps this at 5 by default.
         */
        private int maxAttempts = 3;

        /** Delay before the first retry; doubles (up to {@code max-backoff}) after each. */
        private java.time.Duration initialBackoff = java.time.Duration.ofMillis(100);

        /** Ceiling for the exponential backoff. */
        private java.time.Duration maxBackoff = java.time.Duration.ofSeconds(1);

        /** Multiplier applied to the backoff after each failed attempt. */
        private double backoffMultiplier = 2.0;

        /**
         * gRPC status codes worth retrying. The default pair is the safe one: the call
         * either never reached the backend ({@code UNAVAILABLE}) or the backend explicitly
         * asked to be retried later ({@code RESOURCE_EXHAUSTED}).
         *
         * <p>Adding codes the backend returns after doing work — {@code INTERNAL},
         * {@code UNKNOWN}, {@code DEADLINE_EXCEEDED} — means a retry can repeat that work.
         */
        private List<String> retryableStatusCodes = new ArrayList<>(List.of("UNAVAILABLE", "RESOURCE_EXHAUSTED"));

        /**
         * Whether to retry methods that are not declared idempotent.
         *
         * <p>False (the default) retries only methods whose proto declares
         * {@code idempotency_level = NO_SIDE_EFFECTS} or {@code IDEMPOTENT}, plus, for
         * REST routes, those bound to a safe HTTP verb (GET/HEAD/OPTIONS) — repeating one
         * of those cannot double-charge a card or create a second order. Most protos never
         * set the option, so expect this to exclude a lot; declare the option on the
         * methods you want retried rather than turning this on.
         *
         * <p>True retries every method matching the status codes. Only sensible when the
         * backend deduplicates requests itself.
         */
        private boolean retryNonIdempotent = false;

        /**
         * Retry budget for the channel: once calls start failing broadly, gRPC stops retrying
         * until enough succeed again, so retries cannot multiply the load on a backend that is
         * already struggling. On by default whenever retry is.
         */
        private ThrottlingConfig throttling = new ThrottlingConfig();
    }

    /**
     * Hedging for one backend ({@code shiguchi.services[].hedging}): gRPC's
     * {@code hedgingPolicy} (gRFC A6). The first attempt goes out at once; while none has
     * answered, another starts every {@code hedging-delay}, up to {@code max-attempts} in flight.
     * The first response wins and the others are cancelled.
     *
     * <p>Hedging sends duplicates <em>while the first is still running</em>, so the backend may
     * execute the call more than once. Only methods safe to repeat are hedged — the same rule as
     * {@code retry} — unless {@code hedge-non-idempotent} is set.
     */
    @Data
    @NoArgsConstructor
    public static class HedgingConfig {
        /** Whether to hedge calls to this backend. Cannot be combined with {@code retry.enabled}. */
        private boolean enabled = false;

        /** Most attempts in flight for one call, the first included (gRPC caps this at 5). */
        private int maxAttempts = 3;

        /**
         * Wait before each additional attempt. Set it near the backend's p95 latency: shorter
         * duplicates most calls, longer rarely helps. {@code 0} sends every attempt at once.
         */
        private java.time.Duration hedgingDelay = java.time.Duration.ofMillis(100);

        /**
         * Failures that do not end the call while other attempts are still running. Any other
         * status from any attempt is returned at once and cancels the rest.
         */
        private List<String> nonFatalStatusCodes = new ArrayList<>(List.of("UNAVAILABLE"));

        /** Hedge methods not declared idempotent too. Only with a backend that deduplicates. */
        private boolean hedgeNonIdempotent = false;

        /** Budget for the extra attempts, as for retries. On by default. */
        private ThrottlingConfig throttling = new ThrottlingConfig();
    }

    /**
     * gRPC retry throttling ({@code retryThrottling} in
     * <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md">gRFC A6</a>),
     * a token bucket per channel. It starts full at {@code max-tokens}; every failed call
     * takes one token, every successful call returns {@code token-ratio}. Retries (and hedged
     * attempts) are only sent while more than half the tokens remain.
     *
     * <p>With the defaults, about five more failures than successes in a row stop retrying,
     * and it takes ten successes to earn back each failure — so a backend that is merely
     * flaky still gets its retries, and one that is failing broadly gets the first attempt
     * only.
     */
    @Data
    @NoArgsConstructor
    public static class ThrottlingConfig {
        /** Whether to budget retries at all. Off lets every retryable call use all its attempts. */
        private boolean enabled = true;

        /** Bucket size, 1 to 1000. Larger tolerates longer failure bursts before retries stop. */
        private int maxTokens = 10;

        /** Tokens a successful call returns, above 0 (gRPC keeps three decimals). */
        private double tokenRatio = 0.1;
    }

    /**
     * Circuit breaker for one backend ({@code shiguchi.services[].circuit-breaker}).
     *
     * <p>Counts failures on the calls the gateway makes to this service. After
     * {@code failure-threshold} consecutive failures the breaker opens and every further
     * call fails immediately with {@code UNAVAILABLE} for {@code open-duration}, sparing
     * both the backend and the caller a pile of doomed requests. It then half-opens and
     * lets {@code half-open-calls} through: all succeeding closes it, any failing reopens it.
     *
     * <p>A breaker is per gateway instance and per service, not per method or per instance
     * of the backend.
     */
    @Data
    @NoArgsConstructor
    public static class CircuitBreakerConfig {
        /** Whether to trip a breaker on this backend's failures. */
        private boolean enabled = false;

        /** Consecutive failures that open the breaker. */
        private int failureThreshold = 5;

        /** How long the breaker stays open before letting a trial call through. */
        private java.time.Duration openDuration = java.time.Duration.ofSeconds(30);

        /** Trial calls allowed while half-open; all must succeed to close the breaker. */
        private int halfOpenCalls = 1;

        /**
         * Status codes counted as failures. Deliberately excludes the codes that mean the
         * caller got it wrong ({@code INVALID_ARGUMENT}, {@code NOT_FOUND},
         * {@code PERMISSION_DENIED}...): a backend answering those is healthy.
         */
        private List<String> failureStatusCodes =
                new ArrayList<>(List.of("UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL", "UNKNOWN"));
    }

    /**
     * Glob patterns selecting which RPC methods are exposed (REST, GraphQL, OpenAPI, UI).
     * Matched against {@code fully.qualified.Service/Method}; a pattern without {@code /}
     * matches the method name alone. Empty = expose everything not excluded.
     * Example: {@code ["my.pkg.UserService/Get*", "List*"]}
     */
    private List<String> includeMethods = new ArrayList<>();

    /**
     * Glob patterns for methods to hide from all surfaces. Exclusion wins over inclusion.
     * Example: {@code ["*&#47;Debug*", "my.pkg.AdminService&#47;*"]}
     */
    private List<String> excludeMethods = new ArrayList<>();

    // Per-service feature flags (null = inherit global setting)
    private Boolean graphqlEnabled;
    private Boolean restEnabled;
    private Boolean openapiEnabled;

    /**
     * Reject query parameters that match no request field on this service's routes (null =
     * inherit {@code rest.request-validation.reject-unknown-query-parameters}).
     */
    private Boolean rejectUnknownQueryParameters;

    /**
     * Backend response headers and trailers copied onto this service's HTTP responses (null =
     * inherit {@code rest.forward-response-headers}; an empty list forwards none).
     */
    private List<String> forwardResponseHeaders;

    /**
     * {@code REST_DOWNSTREAM}: URL of the backend's OpenAPI 3 document (JSON or YAML). The
     * gateway fetches it at startup and on refresh, registers one proxy route per operation,
     * and merges the document into the consolidated spec.
     * Example: "http://downstream-svc/v3/api-docs"
     */
    private String openapiUrl;

    /**
     * {@code REST_DOWNSTREAM}: base URL requests are proxied to ({@code scheme://host[:port]}).
     * Defaults to the scheme and authority of {@link #openapiUrl}, else {@code http://host:port}.
     */
    private String baseUrl;

    /**
     * {@code REST_DOWNSTREAM}: copy the backend's {@code securitySchemes} and per-operation
     * {@code security} requirements into the consolidated spec. Off by default, because the
     * gateway's own {@code openapi.security-schemes} describe how callers authenticate here.
     */
    private boolean inheritSecurity = false;

    /**
     * {@code REST_DOWNSTREAM}: OAuth2/JWT scopes required per operation, keyed by
     * {@code operationId}. Takes precedence over an {@code x-gateway-auth-scopes} vendor
     * extension on the operation. Enforced by the {@code GatewayAuthorizer} before proxying.
     */
    private Map<String, List<String>> operationScopes = new HashMap<>();

    /**
     * {@code REST_DOWNSTREAM}: timeout per operation, keyed by {@code operationId}. Takes
     * precedence over {@link #deadline}, {@code client.default-deadline} and the global
     * {@code client.http-timeout}.
     * Values must be positive. Does not apply to {@code rest-batch-loaders[].timeout-seconds},
     * which governs only the batch loader.
     */
    private Map<String, java.time.Duration> operationTimeouts = new HashMap<>();

    /**
     * {@code REST_DOWNSTREAM}: timeout for fetching the OpenAPI document. Default 10 s.
     */
    private java.time.Duration openapiFetchTimeout = java.time.Duration.ofSeconds(10);

    /**
     * {@code REST_DOWNSTREAM}: prefix for the GraphQL type names generated from the backend's
     * schemas, e.g. {@code Shop} turns {@code Item} into {@code ShopItem}. Empty by default; set
     * it when two backends (or a backend and a proto) define the same schema name, since the
     * GraphQL schema has one flat namespace.
     */
    private String graphqlTypePrefix = "";

    /**
     * {@code REST_DOWNSTREAM}: which media type to talk to the backend in when an operation
     * advertises more than one and a codec for the preferred one is on the classpath.
     */
    private UpstreamContent upstreamContent = new UpstreamContent();

    /**
     * {@code REST_DOWNSTREAM}: where the Avro schemas of this backend come from and how the
     * bytes are framed. Read by the {@code shiguchi-avro} module; ignored without it.
     */
    private AvroConfig avro = new AvroConfig();

    /**
     * Media-type preference for calls to a {@code REST_DOWNSTREAM} backend. The operation
     * must advertise the preferred type in its OpenAPI {@code content} and a codec must be able
     * to serve the schema, else the call falls back to {@link #fallback} (JSON by default).
     */
    @Data
    public static class UpstreamContent {
        /** Media type to prefer, e.g. {@code avro/binary}. Default {@code application/json}. */
        private String preferred = "application/json";

        /** Media types to fall back to, in order. */
        private List<String> fallback = new ArrayList<>(List.of("application/json"));

        public boolean prefersJson() {
            return preferred == null
                    || preferred.isBlank()
                    || preferred.trim().toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
        }
    }

    /** Avro settings of a {@code REST_DOWNSTREAM} backend. */
    @Data
    public static class AvroConfig {
        /** Where the writer schemas come from. */
        private AvroSchemaSource schemaSource = AvroSchemaSource.OPENAPI;

        /**
         * {@code .avsc} files ({@code classpath:} or {@code file:} resources) registered by record
         * name, used with {@link AvroSchemaSource#FILES}. A component schema named like the record
         * (or carrying {@code x-avro-record} naming it) binds to it.
         */
        private List<String> schemaFiles = new ArrayList<>();

        /** How request and response bytes are framed. */
        private AvroWireFormat wireFormat = AvroWireFormat.BINARY;

        /**
         * Base URL of a Confluent-compatible schema registry ({@code http://user:pass@host:8081}).
         * Schemas are registered and their ids fetched when the service loads; requests never
         * call the registry. Required for {@link AvroWireFormat#CONFLUENT}.
         */
        private String schemaRegistryUrl;

        /** Component name → registry subject, for components {@link #subjectStrategy} names wrongly. */
        private Map<String, String> subjects = new HashMap<>();

        private AvroSubjectStrategy subjectStrategy = AvroSubjectStrategy.RECORD_NAME;

        /** Timeout for each registry call at load time. */
        private java.time.Duration registryTimeout = java.time.Duration.ofSeconds(10);
    }

    public enum AvroSchemaSource {
        /** {@code x-avro-schema} on the document's component schemas. */
        OPENAPI,
        /** {@code schema-files} only. */
        FILES,
        /** Both: files first, the document fills the gaps. */
        OPENAPI_AND_FILES
    }

    public enum AvroWireFormat {
        /** Raw datum bytes; the schema is known to both sides ahead of time. */
        BINARY,
        /** Avro single-object encoding: {@code C3 01} marker, 8-byte schema fingerprint, datum. */
        SINGLE_OBJECT,
        /**
         * Confluent wire format: {@code 00} magic byte, 4-byte big-endian schema id from a
         * Confluent-compatible schema registry, datum. Needs a {@code schema-registry-url}.
         */
        CONFLUENT
    }

    /** How a schema's registry subject is named when {@code subjects} does not list it. */
    public enum AvroSubjectStrategy {
        /** The Avro record's full name, e.g. {@code com.acme.inventory.Item}. */
        RECORD_NAME,
        /** The OpenAPI component name, e.g. {@code Item}. */
        COMPONENT_NAME
    }

    /**
     * For STATIC mode: fully-qualified names of the *Grpc stub classes whose services
     * run on this backend. The loader calls getServiceDescriptor() on each and extracts
     * the protobuf ServiceDescriptor via ProtoServiceDescriptorSupplier — no live gRPC
     * connection required at startup.
     */
    private List<String> staticServiceClasses = new ArrayList<>();

    /**
     * Auto-discovery settings for STATIC mode.
     * When classpath = true, ClassGraph scans the listed packages (or entire classpath when
     * scanPackages is empty) for *Grpc outer classes and populates staticServiceClasses automatically.
     */
    private AutoDiscoverConfig autoDiscover = new AutoDiscoverConfig();

    @Data
    @NoArgsConstructor
    public static class AutoDiscoverConfig {
        /** Enable classpath scanning to discover gRPC service stub classes. */
        private boolean classpath = false;
        /** Enable Spring ApplicationContext scan to discover BindableService beans. */
        private boolean springBeans = false;
        /** Packages to scan (e.g. "com.example.api"). Empty = entire classpath. Used by classpath mode. */
        private List<String> scanPackages = new ArrayList<>();
    }

    /**
     * Optional prefix prepended to every HTTP path registered for this service.
     * Lets you namespace routes by service without touching the proto annotations.
     * Example: pathPrefix: /ref  →  GET /ref/v1/inv/types/{id}
     */
    private String pathPrefix;

    /**
     * Give this service's methods that carry no {@code google.api.http} rule a
     * {@code POST /{package.Service}/{Method}} route (null = inherit {@code rest.auto-mapping}).
     */
    private Boolean autoMapping;

    /**
     * Compression for outbound calls to this backend: {@code gzip}, or {@code identity} (the
     * default) for none.
     *
     * <p>Per service rather than global because it is a trade, not an improvement. gzip is worth
     * it for large JSON-ish payloads over a constrained link, and costs CPU on both ends for
     * nothing when messages are small or the hop is a loopback interface. The gateway is often
     * fronting both kinds of backend at once.
     *
     * <p>Only the request direction is controlled here. Whether responses come back compressed is
     * the backend's decision; gRPC advertises support for gzip on every call regardless, so a
     * backend that chooses to compress is understood either way.
     */
    private String compression = "identity";

    /**
     * This service's overrides of {@code shiguchi.json.print}; an unset value inherits the
     * global one. Typical use: {@code preserve-proto-field-names: true} for a backend whose REST
     * clients were built against snake_case JSON.
     */
    private JsonPrintOverride jsonPrint;

    /** Per-service {@code json.print} overrides; null inherits. */
    @Data
    @NoArgsConstructor
    public static class JsonPrintOverride {
        private Boolean preserveProtoFieldNames;
        private Boolean enumsAsInts;
        private Boolean omitDefaultValues;
        private Boolean prettyPrint;
    }

    /**
     * Largest response message accepted from this backend, in bytes; overrides
     * {@code client.max-inbound-message-size} (4 MiB) for this service only. A larger message fails
     * the call with HTTP 502 ({@code RESPONSE_TOO_LARGE}). Raise it for a backend that legitimately
     * returns big messages instead of raising the limit for every backend.
     */
    private Integer maxInboundMessageSize;

    // -------------------------------------------------------------------------
    // Computed properties — not generated by Lombok
    // -------------------------------------------------------------------------

    /**
     * The channel target as configured or, when no {@code target} is set, a label for the
     * backend ({@code discovery-name}, else {@code host:port}) used in routes, logs and the
     * console. {@link #isTargetDerived()} tells the two apart.
     */
    public String getTarget() {
        if (target != null && !target.isBlank()) {
            return target;
        }
        if ((host == null || host.isBlank()) && discoveryName != null) {
            return discoveryName;
        }
        return host + ":" + port;
    }

    /** True when {@link #getTarget()} is a label derived from other fields, not a configured target. */
    public boolean isTargetDerived() {
        return target == null || target.isBlank();
    }

    /** Effective flag: per-service override if set, else global default. */
    public boolean isGraphqlEnabled(boolean globalDefault) {
        return graphqlEnabled != null ? graphqlEnabled : globalDefault;
    }

    public boolean isRestEnabled(boolean globalDefault) {
        return restEnabled != null ? restEnabled : globalDefault;
    }

    public boolean isOpenapiEnabled(boolean globalDefault) {
        return openapiEnabled != null ? openapiEnabled : globalDefault;
    }

    public boolean isRejectUnknownQueryParameters(boolean globalDefault) {
        return rejectUnknownQueryParameters != null ? rejectUnknownQueryParameters : globalDefault;
    }

    /** Response headers to forward: this service's list when set, else {@code globalDefault}. */
    public List<String> effectiveForwardResponseHeaders(List<String> globalDefault) {
        return forwardResponseHeaders != null ? forwardResponseHeaders : globalDefault;
    }

    /** Effective inbound message cap: per-service value if set, else {@code client.max-inbound-message-size}. */
    public int effectiveMaxInboundMessageSize(int globalDefault) {
        return maxInboundMessageSize != null ? maxInboundMessageSize : globalDefault;
    }

    /** Effective unary-call deadline: per-service value if set, else the global default. */
    public java.time.Duration effectiveDeadline(java.time.Duration globalDefault) {
        return deadline != null ? deadline : globalDefault;
    }

    /**
     * Timeout for a call to this {@code REST_DOWNSTREAM} service, most specific first: the operation's
     * own {@code operation-timeouts} entry, then {@link #deadline} if positive, then
     * {@code client.default-deadline} if positive, then {@code client.http-timeout}. A zero or
     * negative deadline does not disable the HTTP timeout. One helper for the GraphQL dispatch and
     * the REST passthrough, so the two cannot drift.
     *
     * @param operationId the operation, or null when unknown (service-level resolution)
     */
    public java.time.Duration effectiveHttpTimeout(String operationId, ClientConfig client) {
        java.time.Duration fromYaml = operationId != null ? operationTimeouts.get(operationId) : null;
        if (isPositive(fromYaml)) return fromYaml;
        if (isPositive(deadline)) return deadline;
        if (client != null && isPositive(client.getDefaultDeadline())) return client.getDefaultDeadline();
        java.time.Duration http = client != null ? client.getHttpTimeout() : null;
        return isPositive(http) ? http : java.time.Duration.ofSeconds(30);
    }

    private static boolean isPositive(java.time.Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }

    /** Effective auto-mapping flag: per-service override if set, else {@code rest.auto-mapping}. */
    public boolean isAutoMapping(boolean globalDefault) {
        return autoMapping != null ? autoMapping : globalDefault;
    }

    /**
     * Whether a method passes this service's include/exclude filters.
     * Exclusion wins; an empty include list exposes everything not excluded.
     */
    public boolean isMethodExposed(String serviceFqn, String methodName) {
        String qualified = serviceFqn + "/" + methodName;
        for (String pattern : excludeMethods) {
            if (globMatches(pattern, qualified, methodName)) return false;
        }
        if (includeMethods.isEmpty()) return true;
        for (String pattern : includeMethods) {
            if (globMatches(pattern, qualified, methodName)) return true;
        }
        return false;
    }

    private static boolean globMatches(String pattern, String qualified, String methodName) {
        String candidate = pattern.indexOf('/') >= 0 ? qualified : methodName;
        String regex = ("\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E").replace("\\Q\\E", "");
        return candidate.matches(regex);
    }

    /**
     * Static gRPC metadata headers attached to every outbound call for this service.
     * Installed once at channel creation time (zero per-request overhead).
     */
    private Map<String, String> staticMetadata = new HashMap<>();

    /**
     * Path to a pre-compiled {@code FileDescriptorSet} (.pb) file for this service.
     * Required when {@code mode: PB_FILE}; ignored otherwise.
     */
    private String pbDescriptorPath;

    /**
     * YAML-side field overrides — apply GraphQL schema hints to message fields
     * without modifying proto files.
     *
     * Key format: "MessageTypeName.fieldName" using the proto field name (snake_case).
     */
    private Map<String, FieldOverrideConfig> fieldOverrides = new HashMap<>();

    /**
     * Federation batch loaders for REST_DOWNSTREAM services.
     * Each entry declares how to resolve a specific GraphQL type via a REST endpoint.
     */
    private List<RestBatchLoaderConfig> restBatchLoaders = new ArrayList<>();

    /** True when this is a REST-downstream service (non-gRPC; uses openapiUrl for discovery). */
    public boolean isRestDownstream() {
        return mode == GatewayMode.REST_DOWNSTREAM;
    }

    /**
     * Effective HTTP base URL for a {@code REST_DOWNSTREAM} service: {@link #baseUrl} when set,
     * else the scheme and authority of {@link #openapiUrl}, else {@code http://host:port}.
     */
    public String effectiveBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        if (openapiUrl != null && !openapiUrl.isBlank()) {
            try {
                java.net.URI uri = new java.net.URI(openapiUrl);
                if (uri.getScheme() != null && uri.getAuthority() != null) {
                    return uri.getScheme() + "://" + uri.getAuthority();
                }
            } catch (java.net.URISyntaxException ignored) {
                // fall through to host:port
            }
        }
        return "http://" + host + ":" + port;
    }
}
