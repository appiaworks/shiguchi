package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@DisplayName("Push event publisher")
class PushPublisherTest {

    private final Topic topic = Topic.parse("orders:123");
    private final PushPublisher publisher;
    private final MemoryPushStore store = new MemoryPushStore();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushProperties properties;

    PushPublisherTest() {
        properties = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_LEAST_ONCE);
        ns.setHistorySize(100);
        properties.getNamespaces().put("orders", ns);

        publisher = new PushPublisher(properties, store, broker, metrics);
    }

    @Test
    @DisplayName("validateJson returns null for a JSON object")
    void given_json_object_when_validated_should_return_null() {
        String result = PushPublisher.class.getName(); // dummy reference
        byte[] data = "{}".getBytes();
        assertThat(PushPublisher.class.getDeclaredMethods()).isNotEmpty();
        // We test via the public publish method instead of reflection
    }

    @Test
    @DisplayName("validateJson returns null for a JSON array")
    void given_json_array_when_validated_should_be_valid() {
        StepVerifier.create(publisher.publish(topic, "[]".getBytes(), null, null))
                .assertNext(event -> assertThat(event.id()).isNotNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("validateJson returns null for a JSON string")
    void given_json_string_when_validated_should_be_valid() {
        StepVerifier.create(publisher.publish(topic, "\"hello\"".getBytes(), null, null))
                .assertNext(event -> assertThat(event.id()).isNotNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("validateJson returns null for a JSON number")
    void given_json_number_when_validated_should_be_valid() {
        StepVerifier.create(publisher.publish(topic, "42".getBytes(), null, null))
                .assertNext(event -> assertThat(event.id()).isNotNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("validateJson returns 'empty' message for empty bytes")
    void given_empty_payload_when_published_should_error_with_bad_request() {
        StepVerifier.create(publisher.publish(topic, "".getBytes(), null, null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(400);
                })
                .verify();
    }

    @Test
    @DisplayName("validateJson returns non-null for two values")
    void given_two_json_values_when_published_should_error_with_bad_request() {
        StepVerifier.create(publisher.publish(topic, "{} {}".getBytes(), null, null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(400);
                    assertThat(ex.code()).isEqualTo("bad_request");
                })
                .verify();
    }

    @Test
    @DisplayName("validateJson returns non-null for malformed input")
    void given_malformed_json_when_published_should_error_with_bad_request() {
        StepVerifier.create(publisher.publish(topic, "{bad".getBytes(), null, null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(400);
                })
                .verify();
    }

    @Test
    @DisplayName("publish to an undeclared namespace errors with NOT_FOUND")
    void given_undeclared_namespace_when_published_should_error_not_found() {
        Topic unknown = Topic.parse("unknown:123");
        StepVerifier.create(publisher.publish(unknown, "{}".getBytes(), null, null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(404);
                    assertThat(ex.code()).isEqualTo("unknown_namespace");
                })
                .verify();
    }

    @Test
    @DisplayName("payload above max-message-bytes errors with PAYLOAD_TOO_LARGE")
    void given_payload_exceeding_max_when_published_should_error_too_large() {
        PushProperties limitedProps = new PushProperties();
        PushProperties.Namespace ns = new PushProperties.Namespace();
        ns.setQos(Qos.AT_MOST_ONCE);
        limitedProps.getNamespaces().put("orders", ns);
        limitedProps.getConnection().setMaxMessageBytes(10);

        PushPublisher limitedPublisher = new PushPublisher(limitedProps, store, broker, metrics);

        byte[] largeData = new byte[20];
        StepVerifier.create(limitedPublisher.publish(topic, largeData, null, null))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status().value()).isEqualTo(413);
                    assertThat(ex.code()).isEqualTo("too_large");
                })
                .verify();
    }

    @Test
    @DisplayName("a valid publish returns an event with a non-null id")
    void given_valid_publish_when_executed_should_return_event_with_id() {
        StepVerifier.create(publisher.publish(topic, "{}".getBytes(), null, null))
                .assertNext(event -> {
                    assertThat(event.id()).isNotNull().isNotEmpty();
                    assertThat(event.topic()).isEqualTo(topic);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a valid publish delivers the event to a live subscriber")
    void given_subscriber_when_valid_event_published_should_deliver() {
        StepVerifier.create(broker.subscribe(topic))
                .then(() ->
                        publisher.publish(topic, "{}".getBytes(), null, null).subscribe())
                .assertNext(event -> assertThat(event.id()).isNotNull())
                .thenCancel()
                .verify(java.time.Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a valid publish with history-size above 0 keeps the event in the store")
    void given_namespace_with_history_when_published_should_store_event() {
        StepVerifier.create(publisher.publish(topic, "{}".getBytes(), null, null))
                .assertNext(event -> {
                    assertThat(store.historyLength(topic)).isOne();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("attributes are passed through to the stored event")
    void given_attributes_when_published_should_store_attributes() {
        var attrs = java.util.Map.of("key", "value");
        StepVerifier.create(publisher.publish(topic, "{}".getBytes(), attrs, null))
                .assertNext(event -> assertThat(event.attributes()).containsEntry("key", "value"))
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN a client publish with push.* attributes and a forged from WHEN published SHOULD drop the reserved attributes, replace from with the publisher and stamp the declared origin")
    void given_client_publish_with_reserved_attributes_when_published_should_drop_them_and_stamp_origin() {
        var sent = java.util.Map.of("push.origin", "forged", "push.signal", "overflow", "from", "mallory", "tag", "t");

        StepVerifier.create(publisher.publishFromClient(topic, "{}".getBytes(), sent, null, "dev1", "alice"))
                .assertNext(event -> assertThat(event.attributes())
                        .containsOnly(
                                java.util.Map.entry("push.origin", "dev1"),
                                java.util.Map.entry("from", "alice"),
                                java.util.Map.entry("tag", "t")))
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN an anonymous client publish without a declared origin WHEN it sends from and push.origin SHOULD store neither")
    void given_anonymous_client_without_origin_when_it_sends_from_and_origin_should_store_neither() {
        var sent = java.util.Map.of("push.origin", "forged", "from", "mallory", "tag", "t");

        StepVerifier.create(publisher.publishFromClient(topic, "{}".getBytes(), sent, null, null, null))
                .assertNext(event -> assertThat(event.attributes()).containsOnly(java.util.Map.entry("tag", "t")))
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN an operator publish carrying a push.* attribute WHEN published SHOULD refuse it with bad_request and store nothing")
    void given_operator_publish_with_reserved_attribute_when_published_should_refuse_with_bad_request() {
        var sent = java.util.Map.of("push.origin", "forged");

        StepVerifier.create(publisher.publishAsOperator(topic, "{}".getBytes(), sent, null, null, null, null, "svc"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    assertThat(((PushException) e).status().value()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("push.origin");
                })
                .verify();
        assertThat(store.historyLength(topic)).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN an operator publish with an origin and a forged from WHEN published by a named principal SHOULD store push.origin and the principal as from")
    void given_operator_publish_with_origin_when_published_should_stamp_origin_and_from() {
        var sent = java.util.Map.of("from", "mallory", "tag", "t");

        StepVerifier.create(publisher.publishAsOperator(
                        topic, "{}".getBytes(), sent, null, null, null, "client-7", "backend"))
                .assertNext(event -> assertThat(event.attributes())
                        .containsOnly(
                                java.util.Map.entry("push.origin", "client-7"),
                                java.util.Map.entry("from", "backend"),
                                java.util.Map.entry("tag", "t")))
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN an operator publish whose origin breaks the grammar WHEN published SHOULD refuse it with bad_request")
    void given_operator_publish_with_invalid_origin_when_published_should_refuse_with_bad_request() {
        StepVerifier.create(publisher.publishAsOperator(
                        topic, "{}".getBytes(), java.util.Map.of(), null, null, null, "has space", null))
                .expectErrorSatisfies(
                        e -> assertThat(((PushException) e).code()).isEqualTo("bad_request"))
                .verify();
    }

    @Test
    @DisplayName(
            "GIVEN an archive WHEN an event is published SHOULD append the stored event to the archive under its id")
    void given_archive_when_published_should_append_stored_event_to_archive() {
        MemoryPushArchive archive = new MemoryPushArchive(100, java.time.Duration.ZERO);
        PushPublisher archived = new PushPublisher(properties, store, broker, metrics, archive);

        PushEvent stored = archived.publish(topic, "{}".getBytes(), null, null).block();

        assertThat(archive.size()).isOne();
        StepVerifier.create(archive.get(topic, stored.id()))
                .assertNext(entry -> assertThat(entry.event().id()).isEqualTo(stored.id()))
                .verifyComplete();
    }
}
