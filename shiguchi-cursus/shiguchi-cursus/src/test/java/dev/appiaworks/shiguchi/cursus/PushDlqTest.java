package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Push dead-letter queue: bounded record of unpublishable events")
class PushDlqTest {

    private static final byte[] PAYLOAD = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName(
            "GIVEN a queue of three entries WHEN a fourth is added SHOULD evict the oldest and keep the count of everything ever added")
    void given_fullQueue_when_entryAdded_should_evictOldest() {
        PushDlq dlq = new PushDlq(3);
        PushDlq.Entry first = add(dlq, "kafka-0", "r1");
        add(dlq, "kafka-0", "r2");
        add(dlq, "kafka-0", "r3");

        add(dlq, "kafka-0", "r4");

        assertThat(dlq.size()).isEqualTo(3);
        assertThat(dlq.get(first.id())).isNull();
        assertThat(dlq.added()).isEqualTo(4);
    }

    @Test
    @DisplayName("GIVEN entries from several sources WHEN listed without a source SHOULD return the newest first")
    void given_entries_when_listedWithoutSource_should_returnNewestFirst() {
        PushDlq dlq = new PushDlq(10);
        add(dlq, "kafka-0", "r1");
        add(dlq, "grpc-1", "r2");
        add(dlq, "kafka-0", "r3");

        List<PushDlq.Entry> listed = dlq.list(null, 10);

        assertThat(listed).extracting(PushDlq.Entry::reason).containsExactly("r3", "r2", "r1");
    }

    @Test
    @DisplayName("GIVEN entries from two sources WHEN listed for one source SHOULD return only that source's entries")
    void given_entriesFromTwoSources_when_listedForOne_should_filterBySource() {
        PushDlq dlq = new PushDlq(10);
        add(dlq, "kafka-0", "r1");
        add(dlq, "grpc-1", "r2");
        add(dlq, "kafka-0", "r3");

        List<PushDlq.Entry> listed = dlq.list("kafka-0", 10);

        assertThat(listed).extracting(PushDlq.Entry::reason).containsExactly("r3", "r1");
    }

    @Test
    @DisplayName(
            "GIVEN a stored entry WHEN it is removed SHOULD be gone and removing it again SHOULD report nothing removed")
    void given_storedEntry_when_removed_should_beGone() {
        PushDlq dlq = new PushDlq(10);
        PushDlq.Entry entry = add(dlq, "kafka-0", "r1");

        assertThat(dlq.remove(entry.id())).isTrue();
        assertThat(dlq.get(entry.id())).isNull();
        assertThat(dlq.size()).isZero();
        assertThat(dlq.remove(entry.id())).isFalse();
    }

    @Test
    @DisplayName("GIVEN NONE visibility WHEN an entry is rendered SHOULD hide its key, attributes and payload")
    void given_noneVisibility_when_rendered_should_hideKeyAttributesAndData() {
        PushDlq.Entry entry = addWithDetails(new PushDlq(10));

        Map<String, Object> view = PushDlq.render(entry, PushProperties.PayloadVisibility.NONE);

        assertThat(view).containsEntry("topic", "orders:1").containsEntry("bytes", PAYLOAD.length);
        assertThat(view).doesNotContainKeys("key", "attributes", "data");
    }

    @Test
    @DisplayName(
            "GIVEN METADATA visibility WHEN an entry is rendered SHOULD show its key and attributes but not its payload")
    void given_metadataVisibility_when_rendered_should_showKeyAndAttributesButNotData() {
        PushDlq.Entry entry = addWithDetails(new PushDlq(10));

        Map<String, Object> view = PushDlq.render(entry, PushProperties.PayloadVisibility.METADATA);

        assertThat(view).containsEntry("key", "k-1").containsEntry("attributes", Map.of("from", "kafka"));
        assertThat(view).doesNotContainKey("data");
    }

    @Test
    @DisplayName("GIVEN FULL visibility WHEN an entry is rendered SHOULD show its payload as text")
    void given_fullVisibility_when_rendered_should_showPayloadAsText() {
        PushDlq.Entry entry = addWithDetails(new PushDlq(10));

        Map<String, Object> view = PushDlq.render(entry, PushProperties.PayloadVisibility.FULL);

        assertThat(view).containsEntry("data", new String(PAYLOAD, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("GIVEN bytes that are not printable text WHEN rendered as text SHOULD fall back to base64")
    void given_binaryBytes_when_renderedAsText_should_useBase64Prefix() {
        String text = PushDlq.text(new byte[] {1, 2, 3});

        assertThat(text).startsWith("base64:");
    }

    @Test
    @DisplayName(
            "GIVEN a source event that could not be published WHEN it is dead-lettered SHOULD copy its topic, key, payload and type")
    void given_sourcedEvent_when_deadLettered_should_copyTopicKeyDataAndType() {
        PushDlq dlq = new PushDlq(10);
        SourcedEvent event = new SourcedEvent(
                Topic.parse("orders:1"),
                PAYLOAD,
                Map.of("from", "kafka"),
                "k-1",
                "type.googleapis.com/x.Order",
                new byte[] {9});

        PushDlq.Entry entry = dlq.add("kafka-0", "unknown namespace", event);

        assertThat(entry.topic()).isEqualTo("orders:1");
        assertThat(entry.key()).isEqualTo("k-1");
        assertThat(entry.data()).isEqualTo(PAYLOAD);
        assertThat(entry.typeUrl()).isEqualTo("type.googleapis.com/x.Order");
        assertThat(entry.source()).isEqualTo("kafka-0");
        assertThat(entry.reason()).isEqualTo("unknown namespace");
    }

    // ---- helpers ----

    private static PushDlq.Entry add(PushDlq dlq, String source, String reason) {
        return dlq.add(source, reason, "orders:1", "k-1", PAYLOAD, Map.of(), null, null);
    }

    private static PushDlq.Entry addWithDetails(PushDlq dlq) {
        return dlq.add("kafka-0", "bad payload", "orders:1", "k-1", PAYLOAD, Map.of("from", "kafka"), null, null);
    }
}
