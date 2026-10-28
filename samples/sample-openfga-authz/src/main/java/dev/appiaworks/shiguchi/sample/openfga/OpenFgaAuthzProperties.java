package dev.appiaworks.shiguchi.sample.openfga;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the OpenFGA authorizer. See {@code application.yml} for a worked example. */
@ConfigurationProperties(prefix = "sample.openfga")
public class OpenFgaAuthzProperties {

    /** Master switch. When false the gateway falls back to the built-in scope check alone. */
    private boolean enabled = false;

    /** OpenFGA server URL, e.g. {@code http://localhost:8080}. */
    private String apiUrl = "http://localhost:8080";

    private String storeId;
    private String authorizationModelId;

    /**
     * Per-check timeout. A policy engine on the request hot path must never be able to hang the
     * gateway; on timeout the {@link #failOpen} decision applies.
     */
    private Duration timeout = Duration.ofMillis(300);

    /**
     * What to do when OpenFGA is unreachable, errors, or times out.
     *
     * <p>Defaults to false — fail closed. Failing open turns a policy-engine outage into a silent
     * authorization bypass, so it is opt-in and should only be enabled where availability genuinely
     * outranks the access-control guarantee.
     */
    private boolean failOpen = false;

    /**
     * Route → required tuple. Key is {@code "HTTP_METHOD /path/template"} for REST routes (matching
     * the route's declared binding) or the GraphQL field name for GraphQL routes. Routes absent
     * from this map get no per-object check — only the gateway's scope check applies.
     */
    private Map<String, RouteObjectPermission> routes = new LinkedHashMap<>();

    private Cache cache = new Cache();

    /**
     * Decision cache. Off by default: a cached allow outlives a revoked grant for up to
     * {@code ttl}, and that staleness window should be a deliberate choice, not a default.
     */
    public static class Cache {
        private boolean enabled = false;

        /** How long an allow may be reused. This is the revocation lag you are accepting. */
        private Duration ttl = Duration.ofSeconds(30);

        /**
         * How long a deny may be reused. Kept shorter than {@link #ttl} on purpose: a stale allow
         * is a security failure, a stale deny is only a temporary annoyance for a user who was
         * just granted access.
         */
        private Duration negativeTtl = Duration.ofSeconds(5);

        private long maxSize = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getNegativeTtl() {
            return negativeTtl;
        }

        public void setNegativeTtl(Duration negativeTtl) {
            this.negativeTtl = negativeTtl;
        }

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getStoreId() {
        return storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getAuthorizationModelId() {
        return authorizationModelId;
    }

    public void setAuthorizationModelId(String authorizationModelId) {
        this.authorizationModelId = authorizationModelId;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public Map<String, RouteObjectPermission> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, RouteObjectPermission> routes) {
        this.routes = routes;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }
}
