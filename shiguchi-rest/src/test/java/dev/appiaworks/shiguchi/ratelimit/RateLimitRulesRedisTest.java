package dev.appiaworks.shiguchi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import redis.embedded.RedisServer;

/**
 * {@code shiguchi.rate-limit.rules[]} with {@code backend: REDIS} against a real (embedded) Redis:
 * the wiring {@link RateLimitRulesConfiguration} builds, shared buckets across two gateway
 * instances, and per-rule isolation. The server is an embedded binary, so no Docker is needed.
 */
@DisplayName("Rate-limit rules policy over Redis (real Redis)")
class RateLimitRulesRedisTest {

    private static RedisServer redis;
    private static int port;

    @BeforeAll
    static void startRedis() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        redis = RedisServer.newRedisServer().port(port).build();
        redis.start();
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (redis != null) redis.stop();
    }

    @Configuration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class Infra {
        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory connections() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", port);
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        ReactiveStringRedisTemplate template(LettuceConnectionFactory connections) {
            return new ReactiveStringRedisTemplate(connections);
        }
    }

    private ApplicationContextRunner gateway(String prefix) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Infra.class, RateLimitRulesConfiguration.class)
                .withPropertyValues(
                        "shiguchi.rate-limit.backend=REDIS",
                        "shiguchi.rate-limit.redis.key-prefix=" + prefix,
                        "shiguchi.rate-limit.client-ip-header=X-Forwarded-For",
                        "shiguchi.rate-limit.rules[0].id=writes",
                        "shiguchi.rate-limit.rules[0].tag=write",
                        "shiguchi.rate-limit.rules[0].key=ip",
                        "shiguchi.rate-limit.rules[0].replenish-rate=0.01",
                        "shiguchi.rate-limit.rules[0].burst-capacity=2",
                        "shiguchi.rate-limit.rules[1].id=reads",
                        "shiguchi.rate-limit.rules[1].match=acme.Orders/Get",
                        "shiguchi.rate-limit.rules[1].key=ip",
                        "shiguchi.rate-limit.rules[1].replenish-rate=0.01",
                        "shiguchi.rate-limit.rules[1].burst-capacity=1");
    }

    private static RouteDefinition route(String method, List<String> tags) {
        return RouteDefinition.builder("acme.Orders", method, MethodDescriptor.MethodType.UNARY, "localhost:1")
                .tags(tags)
                .build();
    }

    private static boolean allowed(AssertableApplicationContext ctx, RouteDefinition route, String ip) {
        RateLimitRulesPolicy policy = ctx.getBean(RateLimitRulesPolicy.class);
        AuthorizationRequest request = new AuthorizationRequest(
                route,
                new UsernamePasswordAuthenticationToken("anon", null),
                Map.of(),
                null,
                Map.of("X-Forwarded-For", List.of(ip)),
                AuthorizationRequest.Surface.REST);
        try {
            policy.check(request).block();
            return true;
        } catch (ThrottledException e) {
            assertThat(e.retryAfterSeconds()).isPositive();
            return false;
        }
    }

    @Test
    @DisplayName(
            "GIVEN a tag rule with burst 2 on Redis WHEN one caller sends three requests SHOULD refuse the third with a Retry-After, and another caller is unaffected")
    void given_redisBackend_when_burstExceeded_should_throttleWithRetryAfter() {
        gateway("rules-a:").run(ctx -> {
            RouteDefinition place = route("Place", List.of("write"));

            assertThat(allowed(ctx, place, "203.0.113.1")).isTrue();
            assertThat(allowed(ctx, place, "203.0.113.1")).isTrue();
            assertThat(allowed(ctx, place, "203.0.113.1")).isFalse();
            assertThat(allowed(ctx, place, "203.0.113.2")).isTrue();
        });
    }

    @Test
    @DisplayName(
            "GIVEN two gateway instances on one Redis WHEN the burst is spent across both SHOULD refuse on the second instance, because the bucket is shared")
    void given_twoInstances_when_burstSpentAcrossBoth_should_shareTheBucket() {
        gateway("rules-b:")
                .run(first -> gateway("rules-b:").run(second -> {
                    RouteDefinition place = route("Place", List.of("write"));

                    assertThat(allowed(first, place, "198.51.100.7")).isTrue();
                    assertThat(allowed(second, place, "198.51.100.7")).isTrue();

                    assertThat(allowed(first, place, "198.51.100.7")).isFalse();
                    assertThat(allowed(second, place, "198.51.100.7")).isFalse();
                }));
    }

    @Test
    @DisplayName(
            "GIVEN a route matching only one rule WHEN another rule's bucket is spent SHOULD not charge the unrelated rule")
    void given_twoRules_when_oneBucketSpent_should_keepRulesIsolated() {
        gateway("rules-c:").run(ctx -> {
            RouteDefinition get = route("Get", List.of());
            RouteDefinition place = route("Place", List.of("write"));

            assertThat(allowed(ctx, get, "192.0.2.9")).isTrue();
            assertThat(allowed(ctx, get, "192.0.2.9")).isFalse();

            assertThat(allowed(ctx, place, "192.0.2.9")).isTrue();
            assertThat(allowed(ctx, place, "192.0.2.9")).isTrue();
            assertThat(allowed(ctx, place, "192.0.2.9")).isFalse();
        });
    }

    @Test
    @DisplayName("GIVEN a different key prefix WHEN the same caller sends requests SHOULD get a fresh bucket")
    void given_otherPrefix_when_sameCaller_should_haveFreshBucket() {
        gateway("rules-d1:").run(ctx -> {
            RouteDefinition place = route("Place", List.of("write"));
            assertThat(allowed(ctx, place, "192.0.2.50")).isTrue();
            assertThat(allowed(ctx, place, "192.0.2.50")).isTrue();
            assertThat(allowed(ctx, place, "192.0.2.50")).isFalse();
        });
        gateway("rules-d2:").run(ctx -> {
            RouteDefinition place = route("Place", List.of("write"));
            assertThat(allowed(ctx, place, "192.0.2.50")).isTrue();
        });
    }
}
