package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

@DisplayName("Push actuator endpoint delivery views")
class PushEndpointDeliveriesTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final PushDeliveries deliveries;
    private final PushEndpoint endpoint;

    PushEndpointDeliveriesTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setDeliveryLog(true);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        deliveries = new PushDeliveries(props, store);
        connections.setDeliveries(deliveries);
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics, PushArchive.none());
        PushReport report = new PushReport(
                props,
                broker,
                store,
                metrics,
                connections,
                publisher,
                PushArchive.none(),
                new PushRepublishJobs(),
                new PushAudit());
        report.setDeliveries(deliveries);
        endpoint = new PushEndpoint(report, props);
    }

    @AfterEach
    void stopDeliveries() {
        deliveries.destroy();
    }

    private static PushEvent event(String topic, String id) {
        return new PushEvent(
                Topic.parse(topic), id, "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null, Instant.now());
    }

    private static Map<String, Object> body(WebEndpointResponse<Object> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName(
            "GIVEN an event delivered twice on an unlogged namespace WHEN its deliveries are read SHOULD report two delivered from this instance")
    void given_eventDeliveredTwice_when_deliveriesRead_should_reportCountsFromInstance() {
        PushConnections.Live live = connections.open("sse", null);
        PushEvent delivered = event("ticker:1", "5");
        live.delivered(delivered);
        live.delivered(delivered);

        WebEndpointResponse<Object> response = endpoint.deliveries("messages", "5", "deliveries", "ticker:1", null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response))
                .containsEntry("delivered", 2L)
                .containsEntry("dropped", 0L)
                .containsEntry("logged", false)
                .containsEntry("scope", "instance");
    }

    @Test
    @DisplayName("GIVEN no topic parameter WHEN deliveries are read SHOULD answer 400 bad request")
    void given_noTopic_when_deliveriesRead_should_return400() {
        WebEndpointResponse<Object> response = endpoint.deliveries("messages", "5", "deliveries", null, null);

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN the jobs section instead of messages WHEN deliveries are read SHOULD answer 404 not found")
    void given_jobsSection_when_deliveriesRead_should_return404() {
        WebEndpointResponse<Object> response = endpoint.deliveries("jobs", "5", "deliveries", "ticker:1", null);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN an unknown action instead of deliveries WHEN read SHOULD answer 404 not found")
    void given_unknownAction_when_read_should_return404() {
        WebEndpointResponse<Object> response = endpoint.deliveries("messages", "5", "foo", "ticker:1", null);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN a limit of zero WHEN deliveries are read SHOULD answer 400 bad request")
    void given_zeroLimit_when_deliveriesRead_should_return400() {
        WebEndpointResponse<Object> response = endpoint.deliveries("messages", "5", "deliveries", "ticker:1", "0");

        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("GIVEN a topic without a namespace separator WHEN deliveries are read SHOULD answer 400 bad request")
    void given_topicWithoutColon_when_deliveriesRead_should_return400() {
        WebEndpointResponse<Object> response = endpoint.deliveries("messages", "5", "deliveries", "nocolon", null);

        assertThat(response.getStatus()).isEqualTo(400);
    }
}
