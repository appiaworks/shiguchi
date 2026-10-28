package dev.appiaworks.shiguchi.bff.starter;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ReactorResourceFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the token-refresh end-to-end tests share: gateway instances booted the way an application
 * would, sessions seeded straight into a store with a refresh token the stub issuer knows, and a
 * burst of simultaneous requests. Mirrors {@code spring-boot3/shiguchi-bff-starter}.
 */
final class BffRefreshSupport {

    static final String COOKIE = "gw-session";

    private BffRefreshSupport() {}

    /**
     * Each instance gets event loops of its own: Boot's default {@code ReactorResourceFactory} uses
     * reactor-netty's global resources and disposes them when its context closes, which would take
     * the other instances' servers down with it. Not a {@code @Configuration}: the gateway's
     * package scan would register it everywhere.
     */
    static class OwnResources {
        @Bean
        ReactorResourceFactory reactorResourceFactory() {
            ReactorResourceFactory factory = new ReactorResourceFactory();
            factory.setUseGlobalResources(false);
            return factory;
        }
    }

    /** The properties every refresh test gateway has: authenticated, one provider (the stub), one application, one backend. */
    static List<String> properties(StubIssuer stub, Server backend, Path descriptors) {
        return new ArrayList<>(List.of(
                "server.port=0",
                "spring.main.banner-mode=off",
                "shiguchi.ui.enabled=false",
                "shiguchi.openapi.enabled=false",
                "shiguchi.graphql.enabled=false",
                "shiguchi.security.mode=AUTHENTICATED",
                "shiguchi.bff.enabled=true",
                "shiguchi.bff.cookie.name=" + COOKIE,
                "shiguchi.bff.cookie.secure=false",
                "shiguchi.bff.providers.idp.issuer-uri=" + stub.issuer(),
                "shiguchi.bff.providers.idp.client-id=gateway",
                "shiguchi.bff.providers.idp.client-secret=secret",
                "shiguchi.bff.applications.a.origins=https://a.example.com",
                "shiguchi.bff.applications.a.providers=idp",
                "shiguchi.bff.applications.a.return-to=https://a.example.com/app/",
                "shiguchi.services[0].name=echo",
                "shiguchi.services[0].mode=PB_FILE",
                "shiguchi.services[0].pb-descriptor-path=" + descriptors,
                "shiguchi.services[0].host=localhost",
                "shiguchi.services[0].port=" + backend.getPort()));
    }

    static ConfigurableApplicationContext start(Class<?> app, List<String> properties) {
        return new SpringApplicationBuilder(app, OwnResources.class)
                .web(WebApplicationType.REACTIVE)
                .properties(properties.toArray(String[]::new))
                .run();
    }

    static int port(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    /**
     * Writes a signed-in session for {@code alice} to the store: a real access token from the stub
     * that expires {@code tokenLeft} from now (negative: already expired) and a refresh token the
     * stub accepts. Returns the cookie value.
     */
    static String seed(BffSessionStore store, StubIssuer stub, Duration tokenLeft) {
        return seed(store, stub, tokenLeft, "alice", "sid-1");
    }

    /** As {@link #seed(BffSessionStore, StubIssuer, Duration)} for another user, with the issuer session id {@code sid}. */
    static String seed(BffSessionStore store, StubIssuer stub, Duration tokenLeft, String subject, String sid) {
        String cookie = BffSessionKeys.newCookieValue();
        Instant now = Instant.now();
        store.save(
                        new BffSession(
                                BffSessionKeys.key(cookie),
                                subject,
                                "idp",
                                "a",
                                sid,
                                "Alice",
                                null,
                                null,
                                stub.accessToken(subject),
                                now.plus(tokenLeft),
                                stub.issueRefreshToken(subject),
                                "id-token-0",
                                now,
                                now,
                                0),
                        Duration.ofHours(1))
                .block();
        return cookie;
    }

    /** What a request to the echo route got: the status and the {@code Authorization} the backend saw. */
    record Reply(int status, String body, Integer retryAfter) {

        /** The bearer token the backend saw, or {@code null}. */
        String bearer() {
            int start = body.indexOf("Bearer ");
            if (start < 0) return null;
            int end = body.indexOf('|', start);
            return body.substring(start + "Bearer ".length(), end < 0 ? body.length() : end);
        }
    }

    static Mono<Reply> echo(WebClient client, String cookie) {
        return client.get()
                .uri("/v1/echo/hi")
                .cookie(COOKIE, cookie)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new Reply(
                                response.statusCode().value(),
                                body,
                                response.headers().header("Retry-After").stream()
                                        .findFirst()
                                        .map(Integer::valueOf)
                                        .orElse(null))));
    }

    /** {@code perInstance} simultaneous requests to each of the gateways, interleaved. */
    static List<Reply> burst(List<WebClient> clients, int perInstance, String cookie) {
        return Flux.range(0, perInstance * clients.size())
                .flatMap(i -> echo(clients.get(i % clients.size()), cookie), perInstance * clients.size())
                .collectList()
                .block(Duration.ofSeconds(60));
    }

    /** Id of the one test key. */
    static final String KEY_ID = "e2e1";

    /** The test key ring: 32 fixed bytes, for tests only. */
    static List<String> encryption() {
        return List.of(
                "shiguchi.bff.session.encryption.active=" + KEY_ID,
                "shiguchi.bff.session.encryption.keys." + KEY_ID + "=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    /** A back-channel logout token for {@code subject} and {@code sid}, signed by the stub issuer. */
    static String logoutToken(StubIssuer stub, String subject, String sid) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", stub.issuer());
        claims.put("aud", stub.clientId());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(120).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("sub", subject);
        claims.put("sid", sid);
        claims.put("events", Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()));
        return stub.signAs(claims, "logout+jwt");
    }

    /** Waits until the issuer has seen {@code token} revoked (the gateway revokes after answering). */
    static boolean awaitRevoked(StubIssuer stub, String token) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (stub.revokedTokens().contains(token)) return true;
            Thread.sleep(100);
        }
        return false;
    }

    static WebClient client(int port) {
        return WebClient.builder().baseUrl("http://localhost:" + port).build();
    }
}
