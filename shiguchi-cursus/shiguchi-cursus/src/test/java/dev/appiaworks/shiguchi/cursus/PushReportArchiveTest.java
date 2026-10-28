package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Push report archive views")
class PushReportArchiveTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final MemoryPushArchive archive = new MemoryPushArchive(100, Duration.ofHours(1));
    private final PushReport report;

    PushReportArchiveTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics, archive);
        report = new PushReport(
                props,
                broker,
                store,
                metrics,
                new PushConnections(),
                publisher,
                archive,
                new PushRepublishJobs(),
                new PushAudit());
    }

    @Test
    @DisplayName("GIVEN events on two topics WHEN listed SHOULD return the most recent first")
    void given_events_on_two_topics_when_listed_should_return_newest_first() {
        PushEvent first = publish("orders:1", "{\"n\":1}");
        PushEvent second = publish("ticker:1", "{\"n\":2}");

        List<Map<String, Object>> messages = report.messages(all(null));

        assertThat(messages).extracting(m -> m.get("id")).containsExactly(second.id(), first.id());
    }

    @Test
    @DisplayName("GIVEN events on two topics WHEN listed with a topic filter SHOULD return only that topic")
    void given_events_on_two_topics_when_listed_with_topic_filter_should_return_only_that_topic() {
        publish("orders:1", "{\"n\":1}");
        publish("ticker:1", "{\"n\":2}");

        List<Map<String, Object>> messages = report.messages(all("orders:1"));

        assertThat(messages).extracting(m -> m.get("topic")).containsOnly("orders:1");
    }

    @Test
    @DisplayName("GIVEN the default payload visibility WHEN an event is rendered SHOULD show attributes but not data")
    void given_default_visibility_when_rendered_should_show_attributes_but_not_data() {
        publish("orders:1", "{\"n\":1}", Map.of("region", "eu"));

        Map<String, Object> message = report.messages(all(null)).get(0);

        assertThat(message.get("attributes"))
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("region", "eu");
        assertThat(message).doesNotContainKey("data");
    }

    @Test
    @DisplayName("GIVEN full payload visibility WHEN an event is rendered SHOULD show its data as JSON text")
    void given_full_visibility_when_rendered_should_show_data_as_text() {
        props.getAdmin().setPayloadVisibility(PushProperties.PayloadVisibility.FULL);
        publish("orders:1", "{\"n\":1}");

        Map<String, Object> message = report.messages(all(null)).get(0);

        assertThat(message).containsEntry("data", "{\"n\":1}");
    }

    @Test
    @DisplayName("GIVEN none payload visibility WHEN an event is rendered SHOULD show neither attributes nor data")
    void given_none_visibility_when_rendered_should_show_neither_attributes_nor_data() {
        props.getAdmin().setPayloadVisibility(PushProperties.PayloadVisibility.NONE);
        publish("orders:1", "{\"n\":1}", Map.of("region", "eu"));

        Map<String, Object> message = report.messages(all(null)).get(0);

        assertThat(message).doesNotContainKeys("attributes", "data");
    }

    @Test
    @DisplayName("GIVEN an archived event WHEN fetched by topic and id SHOULD return that event")
    void given_archived_event_when_fetched_by_topic_and_id_should_return_it() {
        PushEvent event = publish("orders:1", "{\"n\":1}");

        Map<String, Object> message = report.message("orders:1", event.id());

        assertThat(message).containsEntry("id", event.id()).containsEntry("topic", "orders:1");
    }

    @Test
    @DisplayName("GIVEN no archived event with the id WHEN fetched SHOULD return null")
    void given_no_event_with_id_when_fetched_should_return_null() {
        publish("orders:1", "{\"n\":1}");

        assertThat(report.message("orders:1", "0000000000000000999")).isNull();
    }

    private PushEvent publish(String topic, String json) {
        return publish(topic, json, Map.of());
    }

    private PushEvent publish(String topic, String json, Map<String, String> attributes) {
        return report.publish(topic, json.getBytes(StandardCharsets.UTF_8), attributes)
                .block();
    }

    private static PushArchive.Query all(String topic) {
        return new PushArchive.Query(topic, null, null, null, null, null, null, null, 100);
    }
}
