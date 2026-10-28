package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@DisplayName("Push ack handler: POST acknowledgements move a session's cursor and malformed requests are refused")
class PushAckHandlerTest {

    private static final Topic INBOX_ALICE = Topic.parse("inbox:alice");
    private static final Topic ALERTS_X = Topic.parse("alerts:x");

    private final PushProperties props = new PushProperties();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushSessions sessions;
    private final PushAckHandler handler;
    private final Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());

    PushAckHandlerTest() {
        PushProperties.Namespace inbox = new PushProperties.Namespace();
        inbox.setQos(Qos.AT_LEAST_ONCE);
        inbox.setHistorySize(10);
        inbox.setSessionExpiry(Duration.ofHours(1));
        inbox.setSessionKey(PushProperties.SessionKey.PRINCIPAL);
        inbox.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);
        props.getNamespaces().put("inbox", inbox);

        PushProperties.Namespace alerts = new PushProperties.Namespace();
        alerts.setQos(Qos.AT_LEAST_ONCE);
        alerts.setHistorySize(10);
        alerts.setSessionExpiry(Duration.ofHours(1));
        alerts.setSessionKey(PushProperties.SessionKey.CLIENT);
        alerts.setAuth(PushProperties.SubscribeAuth.NONE);
        props.getNamespaces().put("alerts", alerts);

        sessions = new PushSessions(props, store, metrics);
        handler = new PushAckHandler(sessions, new TopicAuthorizer(null));
    }

    @Test
    @DisplayName(
            "GIVEN an authenticated caller and a published event WHEN they acknowledge it over HTTP SHOULD respond 200 and move their cursor to it")
    void given_authenticated_caller_and_event_when_acknowledged_should_respond_ok_and_move_cursor() {
        String id = publish("inbox:alice");
        String body = "{\"topic\":\"inbox:alice\",\"id\":\"" + id + "\"}";

        ServerResponse response = handler.ack(post(alice, body)).block();

        assertThat(response.statusCode().value()).isEqualTo(200);
        assertThat(store.cursor("alice", INBOX_ALICE).block()).isEqualTo(id);
    }

    @Test
    @DisplayName(
            "GIVEN an anonymous caller on a principal-keyed namespace WHEN they acknowledge an event SHOULD respond 401 and leave no cursor")
    void given_anonymous_caller_on_principal_keyed_namespace_when_acknowledged_should_respond_unauthorized() {
        String id = publish("inbox:alice");
        String body = "{\"topic\":\"inbox:alice\",\"id\":\"" + id + "\"}";

        ServerResponse response = handler.ack(post(null, body)).block();

        assertThat(response.statusCode().value()).isEqualTo(401);
        assertThat(store.cursor("alice", INBOX_ALICE).block()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN an anonymous caller naming a client session on an open namespace WHEN they acknowledge an event SHOULD respond 200 and move that session's cursor")
    void given_anonymous_caller_with_client_session_when_acknowledged_should_respond_ok_and_move_session_cursor() {
        String id = publish("alerts:x");
        String body = "{\"topic\":\"alerts:x\",\"id\":\"" + id + "\",\"session\":\"dev1\"}";

        ServerResponse response = handler.ack(post(null, body)).block();

        assertThat(response.statusCode().value()).isEqualTo(200);
        assertThat(store.cursor("dev1@-", ALERTS_X).block()).isEqualTo(id);
    }

    @Test
    @DisplayName("GIVEN an acknowledgement without an event id WHEN it is posted SHOULD respond 400")
    void given_acknowledgement_without_id_when_posted_should_respond_bad_request() {
        ServerResponse response =
                handler.ack(post(alice, "{\"topic\":\"inbox:alice\"}")).block();

        assertThat(response.statusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN a body that is not JSON WHEN it is posted SHOULD respond 400")
    void given_body_that_is_not_json_when_posted_should_respond_bad_request() {
        ServerResponse response = handler.ack(post(alice, "not json")).block();

        assertThat(response.statusCode().value()).isEqualTo(400);
    }

    private String publish(String topic) {
        PushEvent event = new PushEvent(
                Topic.parse(topic), null, "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null, Instant.now());
        return store.append(event, 10, null).block().id();
    }

    /** A POST to the ack path; a null caller is anonymous. The body is a Mono, as bodyToMono expects. */
    private static ServerRequest post(Authentication caller, String json) {
        MockServerRequest.Builder builder =
                MockServerRequest.builder().method(HttpMethod.POST).uri(URI.create("http://localhost/push/ack"));
        if (caller != null) {
            builder.principal(caller);
        }
        return builder.body(Mono.just(json.getBytes(StandardCharsets.UTF_8)));
    }
}
