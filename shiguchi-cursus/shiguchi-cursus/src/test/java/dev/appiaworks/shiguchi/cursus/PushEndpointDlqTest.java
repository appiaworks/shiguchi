package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("Push actuator endpoint dead-letter queue views and writes")
class PushEndpointDlqTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final MemoryPushArchive archive = new MemoryPushArchive(100, Duration.ofHours(1));
    private final PushDlq dlq = new PushDlq(100);
    private final PushDlq.Entry withTopic;
    private final PushDlq.Entry withoutTopic;
    private final PushEndpoint endpoint;
    private final SecurityContext alice = context(authenticated("alice"));

    PushEndpointDlqTest() {
        props.getNamespaces().put("orders", new PushProperties.Namespace());
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics, archive);
        withTopic = dlq.add(
                "kafka-orders",
                "payload: bad record",
                "orders:1",
                "1",
                "{\"n\":1}".getBytes(StandardCharsets.UTF_8),
                Map.of(),
                null,
                null);
        withoutTopic = dlq.add(
                "kafka-orders",
                "topic: no key",
                null,
                null,
                "{\"n\":2}".getBytes(StandardCharsets.UTF_8),
                Map.of(),
                null,
                null);
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
        report.setDlq(dlq);
        endpoint = new PushEndpoint(report, props);
    }

    @Test
    @DisplayName(
            "GIVEN two dead-lettered entries WHEN the dlq section is read SHOULD respond 200 with them newest first")
    void given_twoEntries_when_dlqRead_should_respondOkNewestFirst() {
        WebEndpointResponse<Object> response = dlqSection(null);

        assertThat(response.getStatus()).isEqualTo(200);
        List<Map<String, Object>> entries = entriesOf(response);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("id", withoutTopic.id());
        assertThat(entries.get(1)).containsEntry("id", withTopic.id());
    }

    @Test
    @DisplayName("GIVEN entries from one source WHEN the dlq is read for another source SHOULD list none")
    void given_entriesFromOtherSource_when_dlqReadForAnotherSource_should_listNone() {
        WebEndpointResponse<Object> response = dlqSection("other");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(entriesOf(response)).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN no sources are configured WHEN the sources section is read SHOULD respond 200 with an empty list")
    void given_noSources_when_sourcesRead_should_respondOkWithEmptyList() {
        WebEndpointResponse<Object> response =
                endpoint.section("sources", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).containsEntry("sources", List.of());
    }

    @Test
    @DisplayName("GIVEN a dead-lettered entry WHEN fetched by id SHOULD respond 200 with that entry")
    void given_entry_when_fetchedById_should_respondOkWithEntry() {
        WebEndpointResponse<Object> response = endpoint.item("dlq", withTopic.id(), null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).containsEntry("id", withTopic.id());
    }

    @Test
    @DisplayName(
            "GIVEN a dead-lettered entry WHEN deleted SHOULD respond 200, and fetching it afterwards SHOULD respond 404")
    void given_entry_when_deleted_should_respondOkThenNotFound() {
        WebEndpointResponse<Object> deleted = endpoint.delete("dlq", withTopic.id(), null, alice);
        WebEndpointResponse<Object> fetched = endpoint.item("dlq", withTopic.id(), null);

        assertThat(deleted.getStatus()).isEqualTo(200);
        assertThat(fetched.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName(
            "GIVEN a dead-lettered entry with a topic WHEN retried without a target SHOULD respond 200 with an event id and remove the entry")
    void given_entryWithTopic_when_retried_should_respondOkAndRemoveEntry() {
        WebEndpointResponse<Object> response = endpoint.republish(
                "dlq", withTopic.id(), "retry", null, null, null, null, null, null, null, null, alice);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).containsKey("eventId").containsEntry("topic", "orders:1");
        assertThat(endpoint.item("dlq", withTopic.id(), null).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN a dead-lettered entry without a topic WHEN retried without a target SHOULD respond 400")
    void given_entryWithoutTopic_when_retriedWithoutTarget_should_respondBadRequest() {
        WebEndpointResponse<Object> response = endpoint.republish(
                "dlq", withoutTopic.id(), "retry", null, null, null, null, null, null, null, null, alice);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName(
            "GIVEN a dead-lettered entry without a topic WHEN retried to orders:9 SHOULD respond 200 and publish to orders:9")
    void given_entryWithoutTopic_when_retriedWithTarget_should_publishToTarget() {
        WebEndpointResponse<Object> response = endpoint.republish(
                "dlq", withoutTopic.id(), "retry", "orders:9", null, null, null, null, null, null, null, alice);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).containsEntry("topic", "orders:9");
    }

    @Test
    @DisplayName("GIVEN an unknown entry id WHEN retried SHOULD respond 404")
    void given_unknownId_when_retried_should_respondNotFound() {
        WebEndpointResponse<Object> response =
                endpoint.republish("dlq", "no-such-id", "retry", null, null, null, null, null, null, null, null, alice);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    // ---- helpers ----

    private WebEndpointResponse<Object> dlqSection(String source) {
        return endpoint.section("dlq", source, null, null, null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entriesOf(WebEndpointResponse<Object> response) {
        return (List<Map<String, Object>>) body(response).get("dlq");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(WebEndpointResponse<Object> response) {
        return (Map<String, Object>) response.getBody();
    }

    private static Authentication authenticated(String name, String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(
                name,
                "pwd",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        return token;
    }

    /** A security context whose principal is the given authentication (null for anonymous). */
    private static SecurityContext context(Authentication auth) {
        return new SecurityContext() {
            @Override
            public Principal getPrincipal() {
                return auth;
            }

            @Override
            public boolean isUserInRole(String role) {
                return auth != null
                        && auth.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
            }
        };
    }
}
