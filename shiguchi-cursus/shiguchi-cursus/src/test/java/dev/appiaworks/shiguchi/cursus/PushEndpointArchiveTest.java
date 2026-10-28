package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

@DisplayName("Push actuator endpoint archive views and writes")
class PushEndpointArchiveTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final MemoryPushArchive archive = new MemoryPushArchive(100, Duration.ofHours(1));
    private final PushPublisher publisher;
    private final PushEndpoint endpoint;

    PushEndpointArchiveTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
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
        endpoint = new PushEndpoint(report, props);
    }

    @Test
    @DisplayName(
            "GIVEN archived events WHEN messages are read with a topic filter SHOULD respond 200 with that topic's list")
    void given_archived_events_when_messages_read_with_topic_should_respond_ok_with_list() {
        publish("orders:1");
        publish("ticker:1");

        WebEndpointResponse<Object> response = messages("orders:1", null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response).get("messages"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }

    @Test
    @DisplayName("GIVEN no archive configured WHEN messages are read SHOULD respond 404 with no_archive")
    void given_no_archive_when_messages_read_should_respond_not_found_no_archive() {
        PushReport plain = new PushReport(props, broker, store, metrics, connections, publisher);
        PushEndpoint plainEndpoint = new PushEndpoint(plain, props);

        WebEndpointResponse<Object> response =
                plainEndpoint.section("messages", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response)).containsEntry("error", "no_archive");
    }

    @Test
    @DisplayName("GIVEN a limit of zero WHEN messages are read SHOULD respond 400")
    void given_zero_limit_when_messages_read_should_respond_bad_request() {
        WebEndpointResponse<Object> response = messages(null, null, "0");

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN a from value that is not an instant WHEN messages are read SHOULD respond 400 with bad_request")
    void given_malformed_from_when_messages_read_should_respond_bad_request() {
        WebEndpointResponse<Object> response = messages(null, "not-a-date", null);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response)).containsEntry("error", "bad_request");
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN fetched by topic and id SHOULD respond 200 with the event")
    void given_archived_event_when_fetched_should_respond_ok() {
        String id = publish("orders:1");

        WebEndpointResponse<Object> response = endpoint.item("messages", id, "orders:1");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).containsEntry("id", id);
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN republished as a dry run SHOULD respond 200 with dryRun true")
    void given_archived_event_when_dry_run_republished_should_respond_ok_with_dry_run() {
        String id = publish("orders:1");

        WebEndpointResponse<Object> response = endpoint.republish(
                "messages", id, "republish", "orders:1", null, null, true, null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).containsEntry("dryRun", true);
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN tombstoned twice SHOULD respond 200 then 404")
    void given_archived_event_when_tombstoned_twice_should_respond_ok_then_not_found() {
        String id = publish("orders:1");

        WebEndpointResponse<Object> first = endpoint.delete("messages", id, "orders:1", null);
        WebEndpointResponse<Object> second = endpoint.delete("messages", id, "orders:1", null);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN a tombstone was written WHEN the audit log is read SHOULD list the tombstone action")
    @SuppressWarnings("unchecked")
    void given_tombstone_written_when_audit_read_should_list_tombstone_action() {
        String id = publish("orders:1");
        endpoint.delete("messages", id, "orders:1", null);

        WebEndpointResponse<Object> response =
                endpoint.section("audit", null, null, null, null, null, null, null, null, null, null, null);

        List<Map<String, Object>> audit =
                (List<Map<String, Object>>) body(response).get("audit");
        assertThat(audit).extracting(entry -> entry.get("action")).contains("tombstone");
    }

    @Test
    @DisplayName("GIVEN k:v pairs WHEN parsed SHOULD split at the first colon and reject a pair without one")
    void given_attribute_pairs_when_parsed_should_split_on_first_colon_and_reject_malformed() {
        assertThat(PushEndpoint.attributes("a:1,b:x:y")).isEqualTo(Map.of("a", "1", "b", "x:y"));
        assertThatThrownBy(() -> PushEndpoint.attributes("bad")).isInstanceOf(PushException.class);
    }

    @Test
    @DisplayName("GIVEN a limit above the maximum WHEN a query is built SHOULD cap it at one thousand")
    void given_limit_above_maximum_when_query_built_should_cap_at_one_thousand() {
        PushArchive.Query query = PushEndpoint.query(null, null, null, null, null, null, null, null, "5000");

        assertThat(query.limit()).isEqualTo(1000);
    }

    private String publish(String topic) {
        return publisher
                .publish(Topic.parse(topic), "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null)
                .block()
                .id();
    }

    private WebEndpointResponse<Object> messages(String topic, String from, String limit) {
        return endpoint.section("messages", null, null, topic, null, from, null, null, null, null, null, limit);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(WebEndpointResponse<Object> response) {
        return (Map<String, Object>) response.getBody();
    }
}
