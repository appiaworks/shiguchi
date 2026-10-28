package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.CacheVisibility;
import dev.appiaworks.shiguchi.config.RestConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The response cache and coalescing table, driven by a fake clock. */
@DisplayName("Response cache")
class ResponseCacheTest {

    private static RestConfig restConfig(boolean enabled) {
        var config = new RestConfig();
        var cache = new RestConfig.ResponseCacheConfig();
        cache.setEnabled(enabled);
        cache.setMaxSize(org.springframework.util.unit.DataSize.ofMegabytes(64));
        cache.setMaxEntrySize(org.springframework.util.unit.DataSize.ofMegabytes(1));
        config.setResponseCache(cache);
        return config;
    }

    private static RenderedResponse response(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new RenderedResponse(MediaType.APPLICATION_JSON, headers, body.getBytes(StandardCharsets.UTF_8), null);
    }

    @Test
    @DisplayName(
            "GIVEN cache enabled and a policy with serverCache true WHEN get called twice SHOULD return HIT on second with age")
    void given_cacheEnabledAndServerCacheTrue_when_getCalledTwice_should_returnHIT() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);
        var calls = new AtomicInteger(0);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        var loader = Mono.fromCallable(() -> {
            calls.incrementAndGet();
            return response("data");
        });

        assertThat(cache.applies(policy)).isTrue();

        var first = cache.get(key, policy, loader).block();
        assertThat(first.outcome()).isEqualTo(ResponseCache.Outcome.MISS);
        assertThat(calls.get()).isEqualTo(1);

        clock.set(Duration.ofSeconds(10).toNanos());

        var second = cache.get(key, policy, loader).block();
        assertThat(second.outcome()).isEqualTo(ResponseCache.Outcome.HIT);
        assertThat(second.ageSeconds()).isGreaterThanOrEqualTo(10);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN response after TTL but within stale-while-revalidate WHEN get called SHOULD return STALE and trigger background reload")
    void given_responseInStaleWindow_when_get_should_returnSTALEWithReload() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ofSeconds(30), true);
        var calls = new AtomicInteger(0);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        var loader = Mono.fromCallable(() -> {
            calls.incrementAndGet();
            return response("data");
        });

        var first = cache.get(key, policy, loader).block();
        assertThat(first.outcome()).isEqualTo(ResponseCache.Outcome.MISS);

        clock.set(Duration.ofSeconds(75).toNanos());

        var stale = cache.get(key, policy, loader).block();
        assertThat(stale.outcome()).isEqualTo(ResponseCache.Outcome.STALE);

        // The refresh is subscribed while serving the stale entry; this loader answers synchronously.
        assertThat(calls.get()).isEqualTo(2);
        // The refreshed entry is fresh again: the next read is a hit, not another refresh.
        assertThat(cache.get(key, policy, loader).block().outcome()).isEqualTo(ResponseCache.Outcome.HIT);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN response past ttl+stale WHEN get called SHOULD return MISS")
    void given_responsePastStalePeriod_when_get_should_returnMISS() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ofSeconds(30), true);
        var calls = new AtomicInteger(0);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        var loader = Mono.fromCallable(() -> {
            calls.incrementAndGet();
            return response("data");
        });

        var first = cache.get(key, policy, loader).block();
        assertThat(first.outcome()).isEqualTo(ResponseCache.Outcome.MISS);

        clock.set(Duration.ofSeconds(100).toNanos());

        var miss = cache.get(key, policy, loader).block();
        assertThat(miss.outcome()).isEqualTo(ResponseCache.Outcome.MISS);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN response body exceeding max-entry-size WHEN cached SHOULD serve but not store")
    void given_bodyExceedsMaxSize_when_cached_should_serveButNotStore() {
        var clock = new AtomicLong(0);
        var config = restConfig(true);
        config.getResponseCache().setMaxEntrySize(org.springframework.util.unit.DataSize.ofBytes(10));
        var cache = new ResponseCache(config, new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);
        var calls = new AtomicInteger(0);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        var largeBody = "x".repeat(100);
        var loader = Mono.fromCallable(() -> {
            calls.incrementAndGet();
            return response(largeBody);
        });

        var first = cache.get(key, policy, loader).block();
        assertThat(first.outcome()).isEqualTo(ResponseCache.Outcome.MISS);
        assertThat(first.stored()).isFalse();

        var second = cache.get(key, policy, loader).block();
        assertThat(second.outcome()).isEqualTo(ResponseCache.Outcome.MISS);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN response with Set-Cookie header WHEN cached SHOULD not store")
    void given_responseWithSetCookie_when_cached_should_notStore() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        var loader = Mono.fromCallable(() -> {
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(HttpHeaders.SET_COOKIE, "sessionId=abc");
            return new RenderedResponse(MediaType.APPLICATION_JSON, headers, "data".getBytes(), null);
        });

        var first = cache.get(key, policy, loader).block();
        assertThat(first.outcome()).isEqualTo(ResponseCache.Outcome.MISS);
        assertThat(first.stored()).isFalse();
    }

    @Test
    @DisplayName("GIVEN two concurrent requests for same key WHEN loader not complete SHOULD coalesce")
    void given_concurrentRequestsSameKey_when_loaderNotComplete_should_coalesce() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);
        var calls = new AtomicInteger(0);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        var sink = reactor.core.publisher.Sinks.<RenderedResponse>one();
        Mono<RenderedResponse> loader = Mono.defer(() -> {
            calls.incrementAndGet();
            return sink.asMono();
        });

        // Both callers are waiting before the backend answers.
        var first = cache.get(key, policy, loader).toFuture();
        var second = cache.get(key, policy, loader).toFuture();
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();

        sink.tryEmitValue(response("data"));

        assertThat(first.join().outcome()).isEqualTo(ResponseCache.Outcome.MISS);
        assertThat(second.join().outcome()).isEqualTo(ResponseCache.Outcome.COALESCED);
        assertThat(second.join().response()).isSameAs(first.join().response());
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a failed loader WHEN called SHOULD not cache and allow retry")
    void given_failedLoader_when_called_should_notCacheAndAllowRetry() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);
        var calls = new AtomicInteger(0);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        Mono<RenderedResponse> loader = Mono.fromCallable(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("backend error");
        });

        var first = cache.get(key, policy, loader);
        StepVerifier.create(first).expectError(RuntimeException.class).verify();

        var second = cache.get(key, policy, loader);
        StepVerifier.create(second).expectError(RuntimeException.class).verify();

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN invalidatePath called WHEN removing entries SHOULD only remove entries with that path")
    void given_invalidatePath_when_called_should_onlyRemoveMatchingPath() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);

        var key1 = new ResponseCache.Key("test.Service", "/v1/test/1", "rest");
        var key2 = new ResponseCache.Key("test.Service", "/v1/test/2", "rest");

        var loader = Mono.just(response("data"));

        cache.get(key1, policy, loader).block();
        cache.get(key2, policy, loader).block();

        assertThat(cache.stats().get("entries")).isEqualTo(2L);

        cache.invalidatePath("/v1/test/1");

        assertThat(cache.stats().get("entries")).isEqualTo(1L);
    }

    @Test
    @DisplayName("GIVEN evictService called WHEN removing entries SHOULD only remove entries for that service")
    void given_evictService_when_called_should_onlyRemoveMatchingService() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);

        var key1 = new ResponseCache.Key("test.Service1", "/v1/test", "rest");
        var key2 = new ResponseCache.Key("test.Service2", "/v1/test", "rest");

        var loader = Mono.just(response("data"));

        cache.get(key1, policy, loader).block();
        cache.get(key2, policy, loader).block();

        assertThat(cache.stats().get("entries")).isEqualTo(2L);

        long evicted = cache.evictService("test.Service1");
        assertThat(evicted).isEqualTo(1L);
    }

    @Test
    @DisplayName("GIVEN onSchemaReloaded called WHEN clearing SHOULD empty all entries")
    void given_onSchemaReloaded_when_called_should_clearAll() {
        var clock = new AtomicLong(0);
        var cache = new ResponseCache(restConfig(true), new SimpleMeterRegistry(), clock::get);
        var policy = new CachePolicy(Duration.ofSeconds(60), CacheVisibility.AUTO, null, Duration.ZERO, true);
        var key = new ResponseCache.Key("test.Service", "/v1/test", "rest");

        var loader = Mono.just(response("data"));
        cache.get(key, policy, loader).block();

        assertThat(cache.stats().get("entries")).isEqualTo(1L);

        cache.onSchemaReloaded();

        assertThat(cache.stats().get("entries")).isEqualTo(0L);
    }

    @Test
    @DisplayName("GIVEN coalesce-requests enabled with policy null WHEN applies called SHOULD return true")
    void given_coalesceEnabledWithNullPolicy_when_applies_should_returnTrue() {
        var config = restConfig(true);
        config.setCoalesceRequests(true);
        var cache = new ResponseCache(config, new SimpleMeterRegistry(), System::nanoTime);

        assertThat(cache.applies(null)).isTrue();
    }
}
