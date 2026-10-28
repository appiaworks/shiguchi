package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.security.GatewayRequestAttributes;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("BffSecurityContextRepository: the session cookie as the gateway's security context")
class BffSecurityContextRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static JwtAuthenticationToken aliceAuthentication() {
        Jwt jwt = Jwt.withTokenValue("alice-token")
                .header("alg", "none")
                .subject("alice")
                .claim("scope", "read")
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_read")), "alice");
    }

    private static ServerWebExchange exchange(String... authorizationHeader) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/pkg.Svc/Get");
        if (authorizationHeader.length > 0) {
            request.header(HttpHeaders.AUTHORIZATION, authorizationHeader[0]);
        }
        return MockServerWebExchange.from(request.build());
    }

    @Test
    @DisplayName("GIVEN a valid Authorization header WHEN loaded SHOULD defer to bearer and never ask the session")
    void given_bearerHeader_when_loaded_should_notAskSession() {
        AtomicInteger calls = new AtomicInteger();
        BffSecurityContextRepository repository = new BffSecurityContextRepository(exchange -> {
            calls.incrementAndGet();
            return Mono.just(aliceAuthentication());
        });

        SecurityContext context =
                repository.load(exchange("Bearer alice-token")).block(TIMEOUT);

        assertThat(context).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName(
            "GIVEN a malformed Authorization header WHEN loaded SHOULD still defer to bearer and never ask the session")
    void given_malformedAuthorizationHeader_when_loaded_should_notAskSession() {
        AtomicInteger calls = new AtomicInteger();
        BffSecurityContextRepository repository = new BffSecurityContextRepository(exchange -> {
            calls.incrementAndGet();
            return Mono.just(aliceAuthentication());
        });

        SecurityContext context = repository.load(exchange("garbage")).block(TIMEOUT);

        assertThat(context).isNull();
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName(
            "GIVEN no Authorization header and a valid session WHEN loaded SHOULD carry the caller marked as cookie")
    void given_validSessionWithoutHeader_when_loaded_should_carryCookieCaller() {
        JwtAuthenticationToken alice = aliceAuthentication();
        BffSecurityContextRepository repository = new BffSecurityContextRepository(exchange -> Mono.just(alice));
        ServerWebExchange exchange = exchange();

        SecurityContext context = repository.load(exchange).block(TIMEOUT);

        assertThat(context).isNotNull();
        Authentication authentication = context.getAuthentication();
        assertThat(authentication).isSameAs(alice);
        assertThat(exchange.getAttributes().get(GatewayRequestAttributes.AUTHENTICATED_BY))
                .isEqualTo(GatewayRequestAttributes.COOKIE);
        assertThat(BffSecurityContextRepository.isCookieAuthenticated(authentication))
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN no Authorization header and no session WHEN loaded SHOULD be empty without the attribute")
    void given_noSession_when_loaded_should_beEmptyWithoutAttribute() {
        BffSecurityContextRepository repository = new BffSecurityContextRepository(exchange -> Mono.empty());
        ServerWebExchange exchange = exchange();

        assertThat(repository.load(exchange).blockOptional()).isEmpty();
        assertThat(exchange.getAttributes()).doesNotContainKey(GatewayRequestAttributes.AUTHENTICATED_BY);
    }

    @Test
    @DisplayName(
            "GIVEN a session that returns an unauthenticated token WHEN loaded SHOULD be empty without the attribute")
    void given_unauthenticatedToken_when_loaded_should_beEmptyWithoutAttribute() {
        TestingAuthenticationToken anonymous = new TestingAuthenticationToken("alice", null, List.of());
        anonymous.setAuthenticated(false);
        BffSecurityContextRepository repository = new BffSecurityContextRepository(exchange -> Mono.just(anonymous));
        ServerWebExchange exchange = exchange();

        assertThat(repository.load(exchange).blockOptional()).isEmpty();
        assertThat(exchange.getAttributes()).doesNotContainKey(GatewayRequestAttributes.AUTHENTICATED_BY);
    }

    @Test
    @DisplayName("GIVEN no authenticator configured WHEN loaded without a header SHOULD be empty")
    void given_noAuthenticator_when_loaded_should_beEmpty() {
        BffSecurityContextRepository repository = new BffSecurityContextRepository(null);

        assertThat(repository.load(exchange()).blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a request loaded twice WHEN both readers subscribe SHOULD ask the session exactly once")
    void given_requestLoadedTwice_when_bothSubscribe_should_askSessionOnce() {
        AtomicInteger calls = new AtomicInteger();
        BffSecurityContextRepository repository = new BffSecurityContextRepository(exchange -> {
            calls.incrementAndGet();
            return Mono.just(aliceAuthentication());
        });
        ServerWebExchange exchange = exchange();

        SecurityContext first = repository.load(exchange).block(TIMEOUT);
        SecurityContext second = repository.load(exchange).block(TIMEOUT);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("GIVEN a security context WHEN saved SHOULD complete empty without asking the session")
    void given_securityContext_when_saved_should_completeEmptyWithoutAskingSession() {
        AtomicInteger calls = new AtomicInteger();
        BffSecurityContextRepository repository = new BffSecurityContextRepository(exchange -> {
            calls.incrementAndGet();
            return Mono.just(aliceAuthentication());
        });

        StepVerifier.create(repository.save(exchange(), new SecurityContextImpl(aliceAuthentication())))
                .verifyComplete();
        assertThat(calls).hasValue(0);
    }
}
