package dev.appiaworks.shiguchi.config;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.Validator;

/**
 * Startup validation of {@code shiguchi.*}: the mistakes that used to surface as a
 * {@code NullPointerException} in a route loader, a silently ignored service or a channel that
 * connects to {@code null:0} now stop the application with the property path, the offending
 * value and the reason.
 *
 * <p>Field errors are raised on the Java property path ({@code services[0].pbDescriptorPath}), which
 * is what Spring's bean wrapper can resolve; Boot's report prints that path.
 *
 * <p>Registered by the gateway as Spring Boot's {@code configurationPropertiesValidator} bean, so
 * it runs while the properties are bound and a failure is reported by Boot's own
 * {@code BindValidationFailureAnalyzer}. Applications that define their own validator of that name
 * can delegate to {@link #validate(GatewayProperties)} to keep the gateway's checks.
 *
 * <p>Rules, per service: a name, unique; an address for gRPC modes (one of {@code host}+{@code port},
 * {@code discovery-name} or {@code target}), a port in range when a host is set;
 * {@code pb-descriptor-path} for {@code PB_FILE}; stub classes or auto-discovery for {@code STATIC};
 * an absolute http(s) {@code openapi-url} for {@code REST_DOWNSTREAM} (and {@code base-url} when
 * set); TLS {@code cert-chain} and {@code private-key} together or not at all; a non-negative
 * {@code deadline}; a {@code path-prefix} starting with {@code /}; a {@code schema-registry-url}
 * for Avro {@code CONFLUENT} framing and schema files for the {@code FILES} source. Globally:
 * positive keepalive, refresh and retry intervals, ports in range, a positive message-size cap,
 * a UI path starting with {@code /}, a gRPC-Web path prefix that is empty or starts with {@code /},
 * a positive rate limit.
 */
public class GatewayPropertiesValidator implements Validator {

    @Override
    public boolean supports(Class<?> clazz) {
        return GatewayProperties.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        GatewayProperties props = (GatewayProperties) target;
        validateServices(props, errors);
        validateGlobal(props, errors);
    }

    /**
     * Validates programmatically and throws with every problem listed, for callers outside Spring
     * Boot's binding (tests, custom wiring, a host application's own validator).
     *
     * @throws IllegalStateException listing every problem as {@code property: reason}
     */
    public static void validate(GatewayProperties props) {
        List<String> problems = problems(props);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid shiguchi configuration:\n  - " + String.join("\n  - ", problems));
        }
    }

    /** Every problem as {@code shiguchi.<property>: <reason>}; empty when the configuration is valid. */
    public static List<String> problems(GatewayProperties props) {
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(props, "shiguchi");
        new GatewayPropertiesValidator().validate(props, errors);
        List<String> out = new ArrayList<>();
        for (FieldError fe : errors.getFieldErrors()) {
            out.add("shiguchi." + dashed(fe.getField()) + ": " + fe.getDefaultMessage());
        }
        return out;
    }

    /**
     * Field errors are raised on Java property paths ({@code services[0].pbDescriptorPath}, what
     * Spring's bean wrapper can read); this is the same path in the YAML spelling.
     */
    static String dashed(String javaPath) {
        StringBuilder sb = new StringBuilder(javaPath.length() + 8);
        for (int i = 0; i < javaPath.length(); i++) {
            char c = javaPath.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ services

    private static void validateServices(GatewayProperties props, Errors errors) {
        Set<String> names = new HashSet<>();
        List<ServiceDefinition> services = props.getServices();
        for (int i = 0; i < services.size(); i++) {
            ServiceDefinition svc = services.get(i);
            String p = "services[" + i + "]";
            if (svc == null) {
                reject(errors, p, "a service entry must not be empty");
                continue;
            }
            if (isBlank(svc.getName())) {
                reject(errors, p + ".name", "every service needs a name");
            } else if (!names.add(svc.getName())) {
                reject(errors, p + ".name", "service name '" + svc.getName() + "' is used more than once");
            }
            if (svc.getMode() == null) {
                reject(errors, p + ".mode", "mode must be DYNAMIC, STATIC, PB_FILE or REST_DOWNSTREAM");
                continue;
            }
            if (svc.isRestDownstream()) {
                validateRestDownstream(svc, p, errors);
            } else {
                validateGrpcAddress(svc, p, errors);
            }
            switch (svc.getMode()) {
                case PB_FILE -> {
                    if (isBlank(svc.getPbDescriptorPath())) {
                        reject(errors, p + ".pbDescriptorPath", "mode PB_FILE needs the path of a FileDescriptorSet");
                    }
                }
                case STATIC -> {
                    boolean discovers = svc.getAutoDiscover() != null
                            && (svc.getAutoDiscover().isClasspath()
                                    || svc.getAutoDiscover().isSpringBeans());
                    if (!discovers && svc.getStaticServiceClasses().isEmpty()) {
                        reject(
                                errors,
                                p + ".staticServiceClasses",
                                "mode STATIC needs static-service-classes, auto-discover.classpath or auto-discover.spring-beans");
                    }
                }
                default -> {}
            }
            ServiceDefinition.TlsConfig tls = svc.getTls();
            if (tls != null && isBlank(tls.getCertChain()) != isBlank(tls.getPrivateKey())) {
                reject(
                        errors,
                        p + ".tls.certChain",
                        "tls.cert-chain and tls.private-key go together (mTLS needs both)");
            }
            if (svc.getDeadline() != null && svc.getDeadline().isNegative()) {
                reject(errors, p + ".deadline", "deadline must be zero (disabled) or positive");
            }
            if (svc.getPathPrefix() != null
                    && !svc.getPathPrefix().isBlank()
                    && !svc.getPathPrefix().trim().startsWith("/")) {
                reject(errors, p + ".pathPrefix", "path-prefix must start with '/'");
            }
            validateRetry(errors, p, svc.getRetry());
            validateHedging(errors, p, svc.getRetry(), svc.getHedging());
            if (svc.getMaxInboundMessageSize() != null && svc.getMaxInboundMessageSize() <= 0) {
                reject(errors, p + ".maxInboundMessageSize", "must be a positive number of bytes");
            }
            validateCircuitBreaker(errors, p, svc.getCircuitBreaker());
            if (svc.getChannels() < 1 || svc.getChannels() > 64) {
                reject(errors, p + ".channels", "channels must be between 1 and 64");
            }
            if (svc.getMaxConcurrentCalls() < 0) {
                reject(errors, p + ".maxConcurrentCalls", "must be zero (no limit) or positive");
            }
            validateCacheRules(errors, p, svc.getCache());
            validateOutlierDetection(errors, p, svc.getOutlierDetection());
            validateVersions(errors, p, svc);
            ServiceDefinition.AvroConfig avro = svc.getAvro();
            if (avro != null) {
                if (avro.getWireFormat() == ServiceDefinition.AvroWireFormat.CONFLUENT
                        && isBlank(avro.getSchemaRegistryUrl())) {
                    reject(
                            errors,
                            p + ".avro.schemaRegistryUrl",
                            "avro.wire-format CONFLUENT needs a schema registry url");
                }
                if (avro.getSchemaSource() == ServiceDefinition.AvroSchemaSource.FILES
                        && avro.getSchemaFiles().isEmpty()) {
                    reject(errors, p + ".avro.schemaFiles", "avro.schema-source FILES needs at least one schema file");
                }
            }
        }
    }

    private static void validateGrpcAddress(ServiceDefinition svc, String p, Errors errors) {
        boolean hasTarget = !isBlank(svc.getTarget()) && !svc.isTargetDerived();
        boolean hasDiscovery = !isBlank(svc.getDiscoveryName());
        boolean hasHost = !isBlank(svc.getHost());
        if (!hasTarget && !hasDiscovery && !hasHost) {
            reject(errors, p + ".host", "a gRPC backend needs one of host + port, discovery-name or target");
        }
        if (hasHost && (svc.getPort() < 1 || svc.getPort() > 65535)) {
            reject(errors, p + ".port", "port must be between 1 and 65535 when host is set");
        }
        if (hasTarget) {
            String t = svc.getTarget().trim();
            if (!t.contains(":") && !t.contains("/")) {
                reject(errors, p + ".target", "target must be a gRPC target such as dns:///host:port or host:port");
            }
        }
    }

    private static void validateRestDownstream(ServiceDefinition svc, String p, Errors errors) {
        if (isBlank(svc.getOpenapiUrl())) {
            reject(errors, p + ".openapiUrl", "mode REST_DOWNSTREAM needs the url of the backend's OpenAPI document");
        } else if (!isHttpUrl(svc.getOpenapiUrl())) {
            reject(errors, p + ".openapiUrl", "openapi-url must be an absolute http(s) URL");
        }
        if (!isBlank(svc.getBaseUrl()) && !isHttpUrl(svc.getBaseUrl())) {
            reject(errors, p + ".baseUrl", "base-url must be an absolute http(s) URL");
        }
        if (svc.getOpenapiFetchTimeout() != null
                && (svc.getOpenapiFetchTimeout().isZero()
                        || svc.getOpenapiFetchTimeout().isNegative())) {
            reject(errors, p + ".openapiFetchTimeout", "openapi-fetch-timeout must be positive");
        }
        if (svc.getOperationTimeouts() != null) {
            svc.getOperationTimeouts().forEach((operationId, timeout) -> {
                if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                    reject(
                            errors,
                            p + ".operationTimeouts",
                            "operation-timeouts[" + operationId + "] must be a positive duration");
                }
            });
        }
    }

    // ------------------------------------------------------------------ global

    private static void validateGlobal(GatewayProperties props, Errors errors) {
        ClientConfig client = props.getClient();
        if (client != null) {
            positive(errors, "client.keepAliveTime", client.getKeepAliveTime());
            positive(errors, "client.keepAliveTimeout", client.getKeepAliveTimeout());
            if (client.getMaxInboundMessageSize() <= 0) {
                reject(errors, "client.maxInboundMessageSize", "must be a positive number of bytes");
            }
            if (client.getDiscoveryRefresh() == null
                    || client.getDiscoveryRefresh().compareTo(Duration.ofMillis(100)) < 0) {
                reject(errors, "client.discoveryRefresh", "must be at least 100ms");
            }
            if (client.getDefaultDeadline() != null
                    && client.getDefaultDeadline().isNegative()) {
                reject(errors, "client.defaultDeadline", "must be zero (disabled) or positive");
            }
            positive(errors, "client.httpTimeout", client.getHttpTimeout());
        }
        SecurityConfig security = props.getSecurity();
        if (security != null
                && (security.getSessionCheckInterval() == null
                        || security.getSessionCheckInterval().compareTo(Duration.ofSeconds(5)) < 0)) {
            reject(errors, "security.sessionCheckInterval", "must be at least 5s");
        }
        GrpcConfig grpc = props.getGrpc();
        if (grpc != null) {
            if (grpc.isEnabled() && (grpc.getPort() < 1 || grpc.getPort() > 65535)) {
                reject(errors, "grpc.port", "port must be between 1 and 65535");
            }
            if (grpc.getRateLimit() != null
                    && grpc.getRateLimit().isEnabled()
                    && grpc.getRateLimit().getRequestsPerSecond() <= 0) {
                reject(errors, "grpc.rateLimit.requestsPerSecond", "must be positive");
            }
        }
        RefreshConfig refresh = props.getRefresh();
        if (refresh != null && refresh.isEnabled() && refresh.getIntervalSeconds() < 1) {
            reject(errors, "refresh.intervalSeconds", "must be at least 1 second");
        }
        StartupConfig startup = props.getStartup();
        if (startup != null
                && (startup.getRetryInterval() == null
                        || startup.getRetryInterval().isNegative())) {
            reject(errors, "startup.retryInterval", "must be zero (disabled) or positive");
        }
        UiConfig ui = props.getUi();
        if (ui != null && (isBlank(ui.getPath()) || !ui.getPath().startsWith("/"))) {
            reject(errors, "ui.path", "must start with '/'");
        }
        GrpcWebConfig grpcWeb = props.getGrpcWeb();
        if (grpcWeb != null
                && grpcWeb.getPathPrefix() != null
                && !grpcWeb.getPathPrefix().isEmpty()
                && !grpcWeb.getPathPrefix().startsWith("/")) {
            reject(errors, "grpcWeb.pathPrefix", "must be empty or start with '/'");
        }
        MergeConfig merge = props.getMerge();
        if (merge != null && merge.getMaxSources() < 1) {
            reject(errors, "merge.maxSources", "must be at least 1");
        }
        RestConfig rest = props.getRest();
        if (rest != null
                && (rest.getMaxRequestBody() == null || rest.getMaxRequestBody().toBytes() <= 0)) {
            reject(errors, "rest.maxRequestBody", "must be a positive size such as 4MB");
        }
        if (rest != null
                && rest.getStreaming() != null
                && rest.getStreaming().getSseKeepalive() != null
                && rest.getStreaming().getSseKeepalive().isNegative()) {
            reject(errors, "rest.streaming.sseKeepalive", "must be zero (disabled) or positive");
        }
        if (rest != null
                && rest.getResponseCache() != null
                && rest.getResponseCache().isEnabled()) {
            var cache = rest.getResponseCache();
            if (cache.getMaxSize() == null || cache.getMaxSize().toBytes() <= 0) {
                reject(errors, "rest.responseCache.maxSize", "must be a positive size such as 64MB");
            }
            if (cache.getMaxEntrySize() == null || cache.getMaxEntrySize().toBytes() <= 0) {
                reject(errors, "rest.responseCache.maxEntrySize", "must be a positive size such as 1MB");
            }
        }
        if (rest != null
                && rest.getCompression() != null
                && rest.getCompression().isEnabled()
                && (rest.getCompression().getMinSize() == null
                        || rest.getCompression().getMinSize().isNegative())) {
            reject(errors, "rest.compression.minSize", "must be zero or a positive size");
        }
        InboundRateLimitConfig rateLimit = props.getRateLimit();
        if (rateLimit != null && rateLimit.isEnabled()) {
            if (rateLimit.getReplenishRate() <= 0) {
                reject(errors, "rateLimit.replenishRate", "must be positive (tokens per second)");
            }
            if (rateLimit.getBurstCapacity() < 1) {
                reject(errors, "rateLimit.burstCapacity", "must be at least 1");
            }
            if (rateLimit.getRequestedTokens() < 1) {
                reject(errors, "rateLimit.requestedTokens", "must be at least 1");
            }
            if (rateLimit.getRequestedTokens() > rateLimit.getBurstCapacity()) {
                reject(errors, "rateLimit.requestedTokens", "must not exceed burst-capacity, or nothing ever passes");
            }
        }
        GraphQlConfig graphql = props.getGraphql();
        if (graphql != null
                && graphql.getDocumentCache() != null
                && graphql.getDocumentCache().isEnabled()) {
            if (graphql.getDocumentCache().getMaxEntries() < 1) {
                reject(errors, "graphql.documentCache.maxEntries", "must be at least 1");
            }
            positive(
                    errors,
                    "graphql.documentCache.expireAfterAccess",
                    graphql.getDocumentCache().getExpireAfterAccess());
        }
        if (graphql != null
                && graphql.getPersistedQueries() != null
                && graphql.getPersistedQueries().isEnabled()) {
            if (graphql.getPersistedQueries().getMaxEntries() < 1) {
                reject(errors, "graphql.persistedQueries.maxEntries", "must be at least 1");
            }
            positive(
                    errors,
                    "graphql.persistedQueries.expireAfterAccess",
                    graphql.getPersistedQueries().getExpireAfterAccess());
        }
        if (graphql != null && graphql.getLimits() != null) {
            if (graphql.getLimits().getMaxDepth() < 0) {
                reject(errors, "graphql.limits.maxDepth", "must be zero (unlimited) or positive");
            }
            if (graphql.getLimits().getMaxComplexity() < 0) {
                reject(errors, "graphql.limits.maxComplexity", "must be zero (unlimited) or positive");
            }
        }
    }

    private static void validateCacheRules(Errors errors, String p, List<ServiceDefinition.CacheRule> rules) {
        if (rules == null) return;
        for (int i = 0; i < rules.size(); i++) {
            ServiceDefinition.CacheRule rule = rules.get(i);
            String r = p + ".cache[" + i + "]";
            if (rule == null) continue;
            if (rule.getMethods() == null || rule.getMethods().isEmpty()) {
                reject(errors, r + ".methods", "a cache rule needs at least one method pattern");
            }
            if (rule.getTtl() == null || rule.getTtl().isNegative()) {
                reject(errors, r + ".ttl", "ttl must be zero (uncacheable) or positive");
            }
            if (rule.getSharedMaxAge() != null && rule.getSharedMaxAge().isNegative()) {
                reject(errors, r + ".sharedMaxAge", "must not be negative");
            }
            if (rule.getStaleWhileRevalidate() != null
                    && rule.getStaleWhileRevalidate().isNegative()) {
                reject(errors, r + ".staleWhileRevalidate", "must not be negative");
            }
        }
    }

    /** A traffic split that can route something, with headers that can be metadata keys. */
    private static void validateVersions(Errors errors, String p, ServiceDefinition svc) {
        ServiceDefinition.VersionsConfig versions = svc.getVersions();
        if (versions == null) return;
        String v = p + ".versions";
        if (versions.routesByVersion() && isBlank(svc.getDiscoveryName())) {
            reject(errors, v, "routing by version needs discovery-name: versions come from registry metadata");
        }
        var traffic = versions.getTraffic();
        if (traffic != null && !traffic.isEmpty()) {
            Set<String> seen = new HashSet<>();
            int total = 0;
            for (int i = 0; i < traffic.size(); i++) {
                var split = traffic.get(i);
                String t = v + ".traffic[" + i + "]";
                if (split == null || isBlank(split.getVersion())) {
                    reject(errors, t + ".version", "a traffic entry needs a version");
                    continue;
                }
                if (!seen.add(split.getVersion().trim())) {
                    reject(errors, t + ".version", "version '" + split.getVersion() + "' is listed twice");
                }
                if (split.getWeight() < 0) reject(errors, t + ".weight", "must be zero or positive");
                total += Math.max(0, split.getWeight());
            }
            if (total == 0) {
                reject(errors, v + ".traffic", "at least one version needs a positive weight, or nothing is routed");
            }
        }
        metadataKey(errors, v + ".stickyHeader", versions.getStickyHeader());
        metadataKey(errors, v + ".previewHeader", versions.getPreviewHeader());
        validateRollout(errors, v + ".rollout", versions.getRollout());
    }

    private static void validateRollout(Errors errors, String r, ServiceDefinition.RolloutConfig rollout) {
        if (rollout == null || !rollout.isEnabled()) return;
        List<Integer> steps = rollout.getSteps();
        if (steps == null || steps.isEmpty()) {
            reject(errors, r + ".steps", "a rollout needs at least one step, ending at 100");
        } else {
            int previous = 0;
            boolean ordered = true;
            for (int i = 0; i < steps.size() && ordered; i++) {
                Integer step = steps.get(i);
                if (step == null || step <= previous || step > 100) {
                    reject(errors, r + ".steps[" + i + "]", "steps must increase, each between 1 and 100");
                    ordered = false;
                } else {
                    previous = step;
                }
            }
            if (ordered && previous != 100) {
                reject(errors, r + ".steps", "the last step must be 100, or the rollout never completes");
            }
        }
        if (rollout.getInterval() == null
                || rollout.getInterval().isNegative()
                || rollout.getInterval().isZero()) {
            reject(errors, r + ".interval", "must be positive");
        }
        if (rollout.getMinCalls() < 1) reject(errors, r + ".minCalls", "must be at least 1");
        if (!(rollout.getMaxErrorRate() >= 0 && rollout.getMaxErrorRate() <= 1)) {
            reject(errors, r + ".maxErrorRate", "must be between 0 and 1, e.g. 0.01 for 1 %");
        }
        if (rollout.getMaxP99() != null
                && (rollout.getMaxP99().isNegative() || rollout.getMaxP99().isZero())) {
            reject(errors, r + ".maxP99", "must be positive, or unset");
        }
        if (rollout.getErrorCodes() != null) {
            for (int i = 0; i < rollout.getErrorCodes().size(); i++) {
                String code = rollout.getErrorCodes().get(i);
                try {
                    io.grpc.Status.Code.valueOf(code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    reject(errors, r + ".errorCodes[" + i + "]", "'" + code + "' is not a gRPC status code");
                }
            }
        }
    }

    /** Null or a valid gRPC ASCII metadata key: lowercase-able token characters, not -bin, not grpc-. */
    private static void metadataKey(Errors errors, String field, String key) {
        if (key == null) return;
        String k = key.trim().toLowerCase(java.util.Locale.ROOT);
        if (k.isEmpty() || !k.matches("[0-9a-z_.-]+") || k.endsWith("-bin") || k.startsWith("grpc-")) {
            reject(
                    errors,
                    field,
                    "must be a header name usable as gRPC metadata (letters, digits, '-', '_', '.'; not grpc-* or *-bin)");
        }
    }

    /** Values gRPC's outlier-detection policy accepts; a bad one fails every call on the channel. */
    private static void validateOutlierDetection(Errors errors, String p, ServiceDefinition.OutlierDetectionConfig od) {
        if (od == null || !od.isEnabled()) return;
        String o = p + ".outlierDetection";
        positive(errors, o + ".interval", od.getInterval());
        positive(errors, o + ".baseEjectionTime", od.getBaseEjectionTime());
        positive(errors, o + ".maxEjectionTime", od.getMaxEjectionTime());
        if (od.getBaseEjectionTime() != null
                && od.getMaxEjectionTime() != null
                && od.getMaxEjectionTime().compareTo(od.getBaseEjectionTime()) < 0) {
            reject(errors, o + ".maxEjectionTime", "must not be shorter than base-ejection-time");
        }
        percent(errors, o + ".maxEjectionPercent", od.getMaxEjectionPercent());
        var fp = od.getFailurePercentage();
        var sr = od.getSuccessRate();
        boolean fpOn = fp != null && fp.isEnabled();
        boolean srOn = sr != null && sr.isEnabled();
        if (!fpOn && !srOn) {
            reject(errors, o, "enable failure-percentage or success-rate, or nothing is ever ejected");
        }
        if (fpOn) {
            percent(errors, o + ".failurePercentage.threshold", fp.getThreshold());
            percent(errors, o + ".failurePercentage.enforcementPercentage", fp.getEnforcementPercentage());
            atLeastOne(errors, o + ".failurePercentage.minimumHosts", fp.getMinimumHosts());
            atLeastOne(errors, o + ".failurePercentage.requestVolume", fp.getRequestVolume());
        }
        if (srOn) {
            if (sr.getStdevFactor() <= 0) reject(errors, o + ".successRate.stdevFactor", "must be positive");
            percent(errors, o + ".successRate.enforcementPercentage", sr.getEnforcementPercentage());
            atLeastOne(errors, o + ".successRate.minimumHosts", sr.getMinimumHosts());
            atLeastOne(errors, o + ".successRate.requestVolume", sr.getRequestVolume());
        }
    }

    private static void percent(Errors errors, String field, int value) {
        if (value < 0 || value > 100) reject(errors, field, "must be between 0 and 100");
    }

    private static void atLeastOne(Errors errors, String field, int value) {
        if (value < 1) reject(errors, field, "must be at least 1");
    }

    // ------------------------------------------------------------------ helpers

    private static void positive(Errors errors, String field, Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            reject(errors, field, "must be a positive duration");
        }
    }

    /**
     * A retry policy gRPC will accept: attempts above one, positive backoffs that grow, and status
     * code names that exist. gRPC rejects a bad service config by failing every call on the
     * channel, so catching it here turns a silent outage into a startup message.
     */
    private static void validateRetry(Errors errors, String p, ServiceDefinition.RetryConfig retry) {
        if (retry == null || !retry.isEnabled()) return;
        if (retry.getMaxAttempts() < 2) {
            reject(errors, p + ".retry.maxAttempts", "max-attempts must be at least 2 (the first try plus one retry)");
        }
        if (isNotPositive(retry.getInitialBackoff())) {
            reject(errors, p + ".retry.initialBackoff", "initial-backoff must be positive");
        }
        if (isNotPositive(retry.getMaxBackoff())) {
            reject(errors, p + ".retry.maxBackoff", "max-backoff must be positive");
        }
        if (!isNotPositive(retry.getInitialBackoff())
                && !isNotPositive(retry.getMaxBackoff())
                && retry.getMaxBackoff().compareTo(retry.getInitialBackoff()) < 0) {
            reject(errors, p + ".retry.maxBackoff", "max-backoff must be at least initial-backoff");
        }
        if (retry.getBackoffMultiplier() <= 0) {
            reject(errors, p + ".retry.backoffMultiplier", "backoff-multiplier must be positive");
        }
        if (retry.getRetryableStatusCodes() == null
                || retry.getRetryableStatusCodes().isEmpty()) {
            reject(
                    errors,
                    p + ".retry.retryableStatusCodes",
                    "list at least one gRPC status code to retry, or disable retry");
        } else {
            rejectBadCodes(errors, p + ".retry.retryableStatusCodes", retry.getRetryableStatusCodes());
        }
        var throttling = retry.getThrottling();
        if (throttling != null && throttling.isEnabled()) {
            // gRFC A6 bounds; outside them gRPC rejects the whole service config at call time.
            if (throttling.getMaxTokens() < 1 || throttling.getMaxTokens() > 1000) {
                reject(errors, p + ".retry.throttling.maxTokens", "max-tokens must be between 1 and 1000");
            }
            if (!(throttling.getTokenRatio() > 0)) {
                reject(errors, p + ".retry.throttling.tokenRatio", "token-ratio must be positive");
            }
        }
    }

    /**
     * Hedging bounds (gRFC A6), and never together with retry: a gRPC method config carries a
     * retryPolicy or a hedgingPolicy, not both.
     */
    private static void validateHedging(
            Errors errors, String p, ServiceDefinition.RetryConfig retry, ServiceDefinition.HedgingConfig hedging) {
        if (hedging == null || !hedging.isEnabled()) return;
        if (retry != null && retry.isEnabled()) {
            reject(
                    errors,
                    p + ".hedging",
                    "retry and hedging are mutually exclusive (a gRPC method config has one or the other);"
                            + " disable one of them");
        }
        if (hedging.getMaxAttempts() < 2) {
            reject(
                    errors,
                    p + ".hedging.maxAttempts",
                    "max-attempts must be at least 2 (the first try plus one hedge)");
        }
        if (hedging.getHedgingDelay() == null || hedging.getHedgingDelay().isNegative()) {
            reject(errors, p + ".hedging.hedgingDelay", "hedging-delay must be zero or positive");
        }
        if (hedging.getNonFatalStatusCodes() != null
                && !hedging.getNonFatalStatusCodes().isEmpty()) {
            rejectBadCodes(errors, p + ".hedging.nonFatalStatusCodes", hedging.getNonFatalStatusCodes());
        }
        var throttling = hedging.getThrottling();
        if (throttling != null && throttling.isEnabled()) {
            if (throttling.getMaxTokens() < 1 || throttling.getMaxTokens() > 1000) {
                reject(errors, p + ".hedging.throttling.maxTokens", "max-tokens must be between 1 and 1000");
            }
            if (!(throttling.getTokenRatio() > 0)) {
                reject(errors, p + ".hedging.throttling.tokenRatio", "token-ratio must be positive");
            }
        }
    }

    private static void validateCircuitBreaker(
            Errors errors, String p, ServiceDefinition.CircuitBreakerConfig breaker) {
        if (breaker == null || !breaker.isEnabled()) return;
        if (breaker.getFailureThreshold() < 1) {
            reject(errors, p + ".circuitBreaker.failureThreshold", "failure-threshold must be at least 1");
        }
        if (isNotPositive(breaker.getOpenDuration())) {
            reject(errors, p + ".circuitBreaker.openDuration", "open-duration must be positive");
        }
        if (breaker.getHalfOpenCalls() < 1) {
            reject(errors, p + ".circuitBreaker.halfOpenCalls", "half-open-calls must be at least 1");
        }
        if (breaker.getFailureStatusCodes() == null
                || breaker.getFailureStatusCodes().isEmpty()) {
            reject(
                    errors,
                    p + ".circuitBreaker.failureStatusCodes",
                    "list at least one gRPC status code to count as a failure, or disable the circuit breaker");
        } else {
            rejectBadCodes(errors, p + ".circuitBreaker.failureStatusCodes", breaker.getFailureStatusCodes());
        }
    }

    /** Reports every unusable status-code name, naming the offender rather than just the field. */
    private static void rejectBadCodes(Errors errors, String field, java.util.List<String> codes) {
        for (String code : codes) {
            try {
                dev.appiaworks.shiguchi.routing.RetryServiceConfig.validate(code);
            } catch (IllegalArgumentException e) {
                reject(errors, field, e.getMessage());
            }
        }
    }

    private static boolean isNotPositive(java.time.Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }

    private static void reject(Errors errors, String field, String message) {
        errors.rejectValue(field, "shiguchi.invalid", message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean isHttpUrl(String s) {
        try {
            URI uri = new URI(s.trim());
            return uri.getScheme() != null
                    && (uri.getScheme().equalsIgnoreCase("http")
                            || uri.getScheme().equalsIgnoreCase("https"))
                    && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
