package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.CookieSessionEnded;
import dev.appiaworks.shiguchi.security.CookieSessions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@DisplayName("Push connections opened with the BFF session cookie")
class PushCookieConnectionTest {

    private static final Duration POLL = Duration.ofMillis(100);

    private final PushProperties properties = new PushProperties();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final ObjectMapper json = new ObjectMapper();
    private final FakeSessions sessions = new FakeSessions();
    private final PushWebSocketHandler handler;
    private final PushCookieSessions cookies = new PushCookieSessions(sessions, POLL, metrics);

    PushCookieConnectionTest() {
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_MOST_ONCE);
        properties.getNamespaces().put("orders", ns);
        MemoryPushBroker broker = new MemoryPushBroker();
        MemoryPushStore store = new MemoryPushStore();
        handler = new PushWebSocketHandler(
                properties,
                broker,
                store,
                new PushPublisher(properties, store, broker, metrics),
                new TopicAuthorizer(null),
                metrics,
                new PushShutdown(),
                new PushTokenAuthenticator(null, null),
                new PushConnections());
        handler.setCookieSessions(cookies);
    }

    @Test
    @DisplayName("GIVEN a cookie socket whose token expires in 1 s WHEN 2.5 s pass SHOULD stay open")
    void given_cookie_socket_when_token_expires_should_stay_open() throws Exception {
        try (FakeSocket socket = new FakeSocket(caller("s1", 1))) {
            socket.send("{\"type\":\"connect\"}");
            socket.await("connected");
            Thread.sleep(2500);

            assertThat(socket.frames("close")).isEmpty();
        }
    }

    @Test
    @DisplayName("GIVEN a bearer socket whose token expires in 1 s WHEN it passes SHOULD close with 4401")
    void given_bearer_socket_when_token_expires_should_close_4401() throws Exception {
        try (FakeSocket socket = new FakeSocket(caller(null, 1))) {
            socket.send("{\"type\":\"connect\"}");

            assertThat(socket.await("close").get("code").asInt()).isEqualTo(4401);
        }
    }

    @Test
    @DisplayName("GIVEN a cookie socket WHEN its session turns inactive SHOULD close with 4440 session ended")
    void given_cookie_socket_when_session_inactive_should_close_4440() throws Exception {
        try (FakeSocket socket = new FakeSocket(caller("s1", 600))) {
            socket.send("{\"type\":\"connect\"}");
            socket.await("connected");
            sessions.active.set(false);

            JsonNode close = socket.await("close");
            assertThat(close.get("code").asInt()).isEqualTo(4440);
            assertThat(close.get("reason").asText()).isEqualTo("session ended");
        }
    }

    @Test
    @DisplayName(
            "GIVEN a cookie socket WHEN the instance publishes CookieSessionEnded for its handle SHOULD close with 4440 at once")
    void given_cookie_socket_when_session_ended_event_should_close_4440() throws Exception {
        PushCookieSessions slow = new PushCookieSessions(sessions, Duration.ofHours(1), metrics);
        handler.setCookieSessions(slow);
        try (FakeSocket socket = new FakeSocket(caller("s1", 600))) {
            socket.send("{\"type\":\"connect\"}");
            socket.await("connected");
            slow.onSessionEnded(new CookieSessionEnded("other"));
            Thread.sleep(200);
            assertThat(socket.frames("close")).isEmpty();
            slow.onSessionEnded(new CookieSessionEnded("s1"));

            assertThat(socket.await("close").get("code").asInt()).isEqualTo(4440);
        }
    }

    @Test
    @DisplayName("GIVEN a cookie socket WHEN the session store fails SHOULD keep it open and keep polling")
    void given_cookie_socket_when_store_fails_should_stay_open() throws Exception {
        try (FakeSocket socket = new FakeSocket(caller("s1", 600))) {
            socket.send("{\"type\":\"connect\"}");
            socket.await("connected");
            sessions.failing.set(true);
            Thread.sleep(600);

            assertThat(socket.frames("close")).isEmpty();
            assertThat(sessions.calls.get()).isGreaterThan(1);

            sessions.failing.set(false);
            sessions.active.set(false);
            assertThat(socket.await("close").get("code").asInt()).isEqualTo(4440);
        }
    }

    @Test
    @DisplayName("GIVEN a cookie socket WHEN a connect frame carries a token SHOULD close with 1008")
    void given_cookie_socket_when_connect_has_token_should_close_1008() throws Exception {
        try (FakeSocket socket = new FakeSocket(caller("s1", 600))) {
            socket.send("{\"type\":\"connect\",\"token\":\"abc\"}");

            JsonNode close = socket.await("close");
            assertThat(close.get("code").asInt()).isEqualTo(1008);
            assertThat(close.get("reason").asText()).isEqualTo("token not accepted on a cookie connection");
        }
    }

    @Test
    @DisplayName("GIVEN a cookie socket WHEN a refresh frame carries a token SHOULD close with 1008")
    void given_cookie_socket_when_refresh_has_token_should_close_1008() throws Exception {
        try (FakeSocket socket = new FakeSocket(caller("s1", 600))) {
            socket.send("{\"type\":\"connect\"}");
            socket.await("connected");
            socket.send("{\"type\":\"refresh\",\"token\":\"abc\"}");

            assertThat(socket.await("close").get("code").asInt()).isEqualTo(1008);
        }
    }

    @Test
    @DisplayName(
            "GIVEN no CookieSessions bean WHEN a cookie caller token expires SHOULD behave as before and close with 4401")
    void given_no_cookie_sessions_when_token_expires_should_close_4401() throws Exception {
        handler.setCookieSessions(new PushCookieSessions(null, POLL, metrics));
        try (FakeSocket socket = new FakeSocket(caller("s1", 1))) {
            socket.send("{\"type\":\"connect\"}");

            assertThat(socket.await("close").get("code").asInt()).isEqualTo(4401);
        }
    }

    @Test
    @DisplayName("GIVEN a session that has ended WHEN ended is awaited SHOULD complete, and never for a bearer caller")
    void given_session_ended_when_awaited_should_complete_only_for_cookie_callers() {
        sessions.active.set(false);

        assertThat(cookies.ended(caller("s1", 600)).blockOptional(Duration.ofSeconds(3)))
                .isEmpty();
        Disposable never = cookies.ended(caller(null, 600)).subscribe();
        assertThat(never.isDisposed()).isFalse();
        never.dispose();
    }

    // ---- fixtures ----

    private static Authentication caller(String handle, long expiresInSeconds) {
        Instant now = Instant.now();
        Jwt jwt = new Jwt(
                "t",
                now.minusSeconds(1),
                now.plusSeconds(expiresInSeconds),
                Map.of("alg", "none"),
                Map.of("sub", "u1"));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES, "u1");
        if (handle != null) auth.setDetails(new CookieAuthenticated(null, handle, "app"));
        return auth;
    }

    private static final class FakeSessions implements CookieSessions {
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicBoolean failing = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Mono<Boolean> active(String handle) {
            calls.incrementAndGet();
            return failing.get() ? Mono.error(new IllegalStateException("store down")) : Mono.just(active.get());
        }
    }

    /** A WebSocketSession with a handshake principal, frames in through a sink, frames out collected as JSON. */
    private final class FakeSocket implements AutoCloseable {
        private final Sinks.Many<WebSocketMessage> in = Sinks.many().unicast().onBackpressureBuffer();
        private final List<JsonNode> out = new CopyOnWriteArrayList<>();
        private final Disposable running;

        FakeSocket(Authentication principal) {
            WebSocketSession session = org.mockito.Mockito.mock(WebSocketSession.class);
            HandshakeInfo info = new HandshakeInfo(
                    URI.create("ws://gw/push/ws"), new HttpHeaders(), Mono.just(principal), "push.v1.json");
            org.mockito.Mockito.when(session.getHandshakeInfo()).thenReturn(info);
            org.mockito.Mockito.when(session.receive()).thenReturn(in.asFlux());
            org.mockito.Mockito.when(session.textMessage(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> text(inv.getArgument(0)));
            org.mockito.Mockito.when(session.close()).thenReturn(Mono.empty());
            org.mockito.Mockito.when(session.close(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Mono.empty());
            org.mockito.Mockito.when(session.send(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(inv -> Flux.from((org.reactivestreams.Publisher<WebSocketMessage>) inv.getArgument(0))
                            .doOnNext(m -> out.add(read(m.getPayloadAsText())))
                            .then());
            running = handler.handle(session).subscribe();
        }

        void send(String frame) {
            in.tryEmitNext(text(frame));
        }

        JsonNode await(String type) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                List<JsonNode> found = frames(type);
                if (!found.isEmpty()) return found.get(0);
                Thread.sleep(10);
            }
            throw new AssertionError("no '" + type + "' frame within 5 s; saw " + out);
        }

        List<JsonNode> frames(String type) {
            return out.stream()
                    .filter(f -> type.equals(f.path("type").asText()))
                    .toList();
        }

        @Override
        public void close() {
            in.tryEmitComplete();
            running.dispose();
        }

        private WebSocketMessage text(String payload) {
            return new WebSocketMessage(
                    WebSocketMessage.Type.TEXT,
                    DefaultDataBufferFactory.sharedInstance.wrap(payload.getBytes(StandardCharsets.UTF_8)));
        }

        private JsonNode read(String text) {
            try {
                return json.readTree(text);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
