package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.config.CacheVisibility;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.time.Duration;

/**
 * How one route's responses may be cached: the {@code Cache-Control} it sends and whether the
 * gateway's response cache keeps them.
 *
 * <p>Comes from the first {@code services[].cache} rule covering the method, else from the proto's
 * {@code cache_ttl_seconds} with the defaults. A rule with {@code ttl: 0} makes a method
 * uncacheable even when its proto declares a TTL.
 *
 * @param ttl freshness lifetime, positive
 * @param staleWhileRevalidate how long past {@code ttl} a stale answer may be served while one
 *     fresh call refreshes it; zero for none
 */
record CachePolicy(
        Duration ttl,
        CacheVisibility visibility,
        Duration sharedMaxAge,
        Duration staleWhileRevalidate,
        boolean serverCache) {

    /** The policy for a route, or null when its responses are not cacheable. */
    static CachePolicy resolve(RouteDefinition route, ServiceDefinition service, boolean serverCacheDefault) {
        ServiceDefinition.CacheRule rule =
                service == null ? null : service.cacheRuleFor(route.getFullServiceName(), route.getMethodName());
        if (rule != null) {
            if (rule.getTtl() == null || rule.getTtl().isZero() || rule.getTtl().isNegative()) return null;
            return new CachePolicy(
                    rule.getTtl(),
                    rule.getVisibility() == null ? CacheVisibility.AUTO : rule.getVisibility(),
                    rule.getSharedMaxAge(),
                    rule.getStaleWhileRevalidate() == null ? Duration.ZERO : rule.getStaleWhileRevalidate(),
                    rule.getServerCache() != null ? rule.getServerCache() : serverCacheDefault);
        }
        if (route.getCacheTtlSeconds() <= 0) return null;
        return new CachePolicy(
                Duration.ofSeconds(route.getCacheTtlSeconds()),
                CacheVisibility.AUTO,
                null,
                Duration.ZERO,
                serverCacheDefault);
    }

    /**
     * The {@code Cache-Control} value. {@code identified} says whether the request names its
     * caller, which makes an {@code AUTO} response {@code private}: without it a shared cache
     * would hand one caller's answer to the next, since caches only refuse to store responses to
     * requests carrying {@code Authorization} — not a cookie or an API-key header.
     */
    String cacheControl(boolean identified) {
        StringBuilder value = new StringBuilder();
        switch (visibility) {
            case PUBLIC -> value.append("public, ");
            case PRIVATE -> value.append("private, ");
            case AUTO -> {
                if (identified) value.append("private, ");
            }
        }
        value.append("max-age=").append(ttl.toSeconds());
        // s-maxage only means something to a shared cache, and a private response never reaches one.
        boolean shared = visibility == CacheVisibility.PUBLIC || (visibility == CacheVisibility.AUTO && !identified);
        if (sharedMaxAge != null && shared) value.append(", s-maxage=").append(sharedMaxAge.toSeconds());
        if (!staleWhileRevalidate.isZero()) {
            value.append(", stale-while-revalidate=").append(staleWhileRevalidate.toSeconds());
        }
        return value.toString();
    }

    /** Whether the response cache keys this route's entries by the caller. */
    boolean perCaller() {
        return visibility != CacheVisibility.PUBLIC;
    }
}
