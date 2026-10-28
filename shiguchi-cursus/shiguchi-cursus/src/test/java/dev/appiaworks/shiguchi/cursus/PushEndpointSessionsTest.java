package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@DisplayName("Push actuator sessions view: lists persistent cursors, filters by prefix and forgets sessions")
class PushEndpointSessionsTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final MemoryPushArchive archive = new MemoryPushArchive(100, Duration.ofHours(1));
    private final PushPublisher publisher;
    private final PushSessions sessions;
    private final TopicAuthorizer authorizer = new TopicAuthorizer(null);
    private final PushEndpoint endpoint;
    private final Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a", List.of());
    private final SecurityContext ctx = context(alice);

    PushEndpointSessionsTest() {
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

        publisher = new PushPublisher(props, store, broker, metrics, archive);
        PushReport report = new PushReport(
                props,
                broker,
                store,
                metrics,
                connections,
                publisher,
                archive,
                new PushRepublishJobs(),
                new PushAudit());
        sessions = new PushSessions(props, store, metrics);
        report.setSessions(sessions);
        endpoint = new PushEndpoint(report, props);
    }

    @Test
    @DisplayName(
            "GIVEN cursors for two sessions WHEN the sessions section is read SHOULD respond 200 with one row per cursor, sorted by session, carrying session, topic, cursor and expiresAt")
    void given_cursors_for_two_sessions_when_sessions_read_should_respond_ok_with_sorted_rows() {
        acknowledge(alice, "inbox:alice", publish("inbox:alice"));
        sessions.ack(null, "dev1", "alerts:x", publish("alerts:x"), authorizer).block();

        WebEndpointResponse<Object> response = sessions(null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        List<Map<String, Object>> rows = rows(response);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("session")).containsExactly("alice", "dev1@-");
        assertThat(rows.get(0)).containsOnlyKeys("session", "topic", "cursor", "expiresAt");
    }

    @Test
    @DisplayName(
            "GIVEN cursors for two sessions WHEN the sessions section is read with a prefix SHOULD respond 200 with only the matching session")
    void given_cursors_for_two_sessions_when_sessions_read_with_prefix_should_respond_ok_with_matching_row() {
        acknowledge(alice, "inbox:alice", publish("inbox:alice"));
        sessions.ack(null, "dev1", "alerts:x", publish("alerts:x"), authorizer).block();

        WebEndpointResponse<Object> response = sessions("dev", null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(rows(response)).extracting(row -> row.get("session")).containsExactly("dev1@-");
    }

    @Test
    @DisplayName(
            "GIVEN a session with a cursor WHEN an admin deletes it SHOULD respond 200 with the cursors dropped and no longer list it")
    void given_session_with_cursor_when_deleted_should_respond_ok_and_stop_listing_it() {
        acknowledge(alice, "inbox:alice", publish("inbox:alice"));
        sessions.ack(null, "dev1", "alerts:x", publish("alerts:x"), authorizer).block();

        WebEndpointResponse<Object> deleted = endpoint.delete("sessions", "alice", null, ctx);

        assertThat(deleted.getStatus()).isEqualTo(200);
        assertThat(deleted.getBody()).isEqualTo(Map.of("session", "alice", "dropped", 1L));
        assertThat(rows(sessions(null, null)))
                .extracting(row -> row.get("session"))
                .containsExactly("dev1@-");
    }

    @Test
    @DisplayName("GIVEN no session with that name WHEN an admin deletes it SHOULD respond 404")
    void given_unknown_session_when_deleted_should_respond_not_found() {
        WebEndpointResponse<Object> response = endpoint.delete("sessions", "nobody", null, ctx);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN the endpoint's sections WHEN they are listed SHOULD include sessions")
    void given_endpoint_sections_when_listed_should_include_sessions() {
        assertThat(PushEndpoint.sections()).contains("sessions");
    }

    private String publish(String topic) {
        PushEvent event = new PushEvent(
                Topic.parse(topic), null, "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null, Instant.now());
        return store.append(event, 10, null).block().id();
    }

    private void acknowledge(Authentication caller, String topic, String id) {
        sessions.ack(caller, null, topic, id, authorizer).block();
    }

    /** The sessions section with a name prefix (q) and a limit; every other filter left unset. */
    private WebEndpointResponse<Object> sessions(String prefix, String limit) {
        return endpoint.section("sessions", prefix, null, null, null, null, null, null, null, null, null, limit);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(WebEndpointResponse<Object> response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("sessions");
    }

    /** A security context whose principal is the given authentication. */
    private static SecurityContext context(Authentication auth) {
        return new SecurityContext() {
            @Override
            public Principal getPrincipal() {
                return auth;
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }
        };
    }
}
