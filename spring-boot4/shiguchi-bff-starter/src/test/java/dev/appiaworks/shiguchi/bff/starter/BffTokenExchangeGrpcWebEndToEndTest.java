package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.bff.testing.StubIssuer;
import io.grpc.Server;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * RFC 8693 token exchange on the gRPC-Web path of the Boot 4 gateway: a cookie caller's call
 * reaches the backend with the token exchanged for that service, not the session's own; a refusal
 * answers PERMISSION_DENIED and an unreachable issuer UNAVAILABLE trailers, never relaying the user's token.
 */
@DisplayName("BFF token exchange on gRPC-Web end to end (Boot 4)")
class BffTokenExchangeGrpcWebEndToEndTest {

    private static final String SERVICE = "lib.Echo";

    private static final StubIssuer STUB = StubIssuer.start();
    private static final Server BACKEND = BffTestBackend.start();
    private static final Path PB_FILE = BffTestBackend.writeDescriptorSet();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        ReactiveJwtDecoder testJwtDecoder() {
            return NimbusReactiveJwtDecoder.withJwkSetUri(STUB.issuer() + "/jwks")
                    .build();
        }
    }

    private static ConfigurableApplicationContext gateway;
    private static WebClient client;
    private static BffSessionStore store;

    @BeforeAll
    static void start() {
        List<String> properties = BffRefreshSupport.properties(STUB, BACKEND, PB_FILE);
        properties.add("shiguchi.grpc-web.enabled=true");
        properties.add("shiguchi.bff.providers.idp.upstream-token-mode=EXCHANGE");
        properties.add("shiguchi.bff.providers.idp.exchange.targets[" + SERVICE + "].audience=orders-api");
        gateway = BffRefreshSupport.start(TestApp.class, properties);
        client = BffRefreshSupport.client(BffRefreshSupport.port(gateway));
        store = gateway.getBean(BffSessionStore.class);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) gateway.close();
        BACKEND.shutdownNow();
        STUB.close();
        Files.deleteIfExists(PB_FILE);
    }

    @BeforeEach
    void resetIssuer() {
        STUB.reset();
    }

    /** What a unary gRPC-Web call got: the frames' bytes as text, in which the echoed bearer and the trailers appear. */
    private record Reply(int httpStatus, String body) {

        int grpcStatus() {
            Matcher m = Pattern.compile("grpc-status: ?(\\d+)").matcher(body);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        }

        String bearer() {
            Matcher m = Pattern.compile("Bearer ([\\w.-]+)").matcher(body);
            return m.find() ? m.group(1) : null;
        }
    }

    private static Reply call(String cookie) {
        // an empty request message: one data frame, flag 0 and length 0
        byte[] emptyFrame = {0, 0, 0, 0, 0};
        return client.post()
                .uri("/lib.Echo/Get")
                .cookie(BffRefreshSupport.COOKIE, cookie)
                .header(HttpHeaders.ORIGIN, "https://a.example.com")
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .bodyValue(emptyFrame)
                .exchangeToMono(r -> r.bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(b -> new Reply(
                                r.statusCode().value(), new String(b, java.nio.charset.StandardCharsets.ISO_8859_1))))
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller WHEN calling over gRPC-Web SHOULD reach the backend with the exchanged token, not the session's")
    void given_cookie_caller_when_calling_should_use_exchanged_token() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession session = store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));

        Reply reply = call(cookie);

        assertThat(reply.grpcStatus()).isZero();
        assertThat(reply.bearer()).isNotBlank().isNotEqualTo(session.accessToken());
        assertThat(STUB.exchangeCalls()).isEqualTo(1);
        assertThat(STUB.lastExchangeRequestBody()).contains("audience=orders-api");
    }

    @Test
    @DisplayName(
            "GIVEN an issuer that refuses the exchange WHEN calling over gRPC-Web SHOULD answer PERMISSION_DENIED without the user's token")
    void given_refused_exchange_when_calling_should_answer_permission_denied() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession session = store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));
        STUB.exchangeStatus(400).exchangeError("invalid_target");

        Reply reply = call(cookie);

        assertThat(reply.grpcStatus()).isEqualTo(7);
        assertThat(reply.bearer()).isNull();
        assertThat(reply.body()).doesNotContain(session.accessToken());
    }

    @Test
    @DisplayName(
            "GIVEN an issuer that is down WHEN calling over gRPC-Web SHOULD answer UNAVAILABLE without the user's token")
    void given_issuer_down_when_calling_should_answer_unavailable() {
        String cookie = BffRefreshSupport.seed(store, STUB, Duration.ofMinutes(5));
        BffSession session = store.find(BffSessionKeys.key(cookie)).block(Duration.ofSeconds(10));
        STUB.exchangeStatus(503).exchangeError("temporarily_unavailable");

        Reply reply = call(cookie);

        assertThat(reply.grpcStatus()).isEqualTo(14);
        assertThat(reply.bearer()).isNull();
        assertThat(reply.body()).doesNotContain(session.accessToken());
    }
}
