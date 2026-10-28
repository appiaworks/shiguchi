package dev.appiaworks.shiguchi.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import java.net.InetSocketAddress;
import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

@DisplayName("DefaultRateLimitKeyResolver")
class DefaultRateLimitKeyResolverTest {

    private static InboundRateLimitConfig config(InboundRateLimitConfig.Key key) {
        var c = new InboundRateLimitConfig();
        c.setKey(key);
        return c;
    }

    private static ServerWebExchange exchange(String principal, String forwardedFor) {
        MockServerHttpRequest.BaseBuilder<?> request =
                MockServerHttpRequest.get("/v1/items").remoteAddress(new InetSocketAddress("192.168.1.9", 5555));
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor);
        MockServerWebExchange.Builder builder = MockServerWebExchange.builder(request.build());
        if (principal != null) builder.principal((Principal) () -> principal);
        return builder.build();
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL_OR_IP WHEN the caller is authenticated SHOULD key on the user, else on the IP")
    void given_principalOrIp_when_resolving_should_preferPrincipal() {
        var resolver = new DefaultRateLimitKeyResolver(config(InboundRateLimitConfig.Key.PRINCIPAL_OR_IP));

        assertThat(resolver.resolve(exchange("alice", null)).block()).isEqualTo("user:alice");
        assertThat(resolver.resolve(exchange(null, null)).block()).isEqualTo("ip:192.168.1.9");
        assertThat(resolver.resolve(exchange("anonymousUser", null)).block()).isEqualTo("ip:192.168.1.9");
    }

    @Test
    @DisplayName("GIVEN PRINCIPAL WHEN the caller is anonymous SHOULD resolve nothing, so the request is not limited")
    void given_principalOnly_when_anonymous_should_resolveNothing() {
        var resolver = new DefaultRateLimitKeyResolver(config(InboundRateLimitConfig.Key.PRINCIPAL));

        assertThat(resolver.resolve(exchange("bob", null)).block()).isEqualTo("user:bob");
        assertThat(resolver.resolve(exchange(null, null)).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN IP WHEN X-Forwarded-For is present SHOULD key on its first hop, else on the socket peer")
    void given_ipKey_when_resolving_should_useForwardedForFirstHop() {
        var resolver = new DefaultRateLimitKeyResolver(config(InboundRateLimitConfig.Key.IP));

        assertThat(resolver.resolve(exchange("alice", "203.0.113.7, 10.0.0.1")).block())
                .isEqualTo("ip:203.0.113.7");
        assertThat(resolver.resolve(exchange("alice", null)).block()).isEqualTo("ip:192.168.1.9");
    }

    @Test
    @DisplayName(
            "GIVEN client-ip-header is empty WHEN X-Forwarded-For is present SHOULD ignore it and use the socket peer")
    void given_noClientIpHeader_when_forwardedForPresent_should_useSocketPeer() {
        var c = config(InboundRateLimitConfig.Key.IP);
        c.setClientIpHeader("");
        var resolver = new DefaultRateLimitKeyResolver(c);

        assertThat(resolver.resolve(exchange(null, "203.0.113.7")).block()).isEqualTo("ip:192.168.1.9");
    }
}
