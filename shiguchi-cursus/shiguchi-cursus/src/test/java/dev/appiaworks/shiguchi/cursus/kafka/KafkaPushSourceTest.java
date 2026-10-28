package dev.appiaworks.shiguchi.cursus.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.cursus.PushDlq;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.SourcedEvent;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import reactor.core.Disposable;

class KafkaPushSourceTest {

    private static final String TENANTS_CONSUMER_THREAD = "push-source-kafka-tenants";

    private static EmbeddedKafkaKraftBroker broker;
    private static String bootstrap;

    @BeforeAll
    static void start() throws Exception {
        broker = new EmbeddedKafkaKraftBroker(1, 1, "orders", "tenants");
        broker.afterPropertiesSet();
        bootstrap = broker.getBrokersAsString();
    }

    @AfterAll
    static void stop() {
        if (broker != null) broker.destroy();
    }

    @Test
    @DisplayName(
            "GIVEN a source on topic orders with start EARLIEST WHEN two records were produced before subscribing SHOULD emit both as events with Kafka attributes")
    void given_earliestSourceAndRecordsProducedBeforeSubscribing_when_subscribed_should_emitBothWithKafkaAttributes()
            throws Exception {
        // Arrange: records exist before the consumer starts, so EARLIEST must read them.
        produce("orders", "1", "{\"id\":\"1\"}");
        produce("orders", "2", "{\"id\":\"2\"}");
        PushProperties.Source config = brokerConfig("kafka-orders", "orders", "orders:{key}");
        config.getKafka().setStart(PushProperties.KafkaStart.EARLIEST);
        KafkaPushSource source = new KafkaPushSource("kafka-orders", config, null, null, new PushDlq(10));

        // Act
        List<SourcedEvent> events = source.events().take(2).collectList().block(Duration.ofSeconds(30));

        // Assert
        assertThat(events).isNotNull().hasSize(2);
        assertThat(events.get(0).topic()).isEqualTo(Topic.parse("orders:1"));
        assertThat(events.get(1).topic()).isEqualTo(Topic.parse("orders:2"));
        assertThat(new String(events.get(0).data(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"1\"}");
        assertThat(new String(events.get(1).data(), StandardCharsets.UTF_8)).isEqualTo("{\"id\":\"2\"}");
        assertThat(events.get(0).key()).isEqualTo("1");
        assertThat(events.get(1).key()).isEqualTo("2");
        assertThat(events.get(0).attributes())
                .containsEntry("kafka_topic", "orders")
                .containsEntry("kafka_partition", "0")
                .containsEntry("kafka_offset", "0");
        assertThat(events.get(1).attributes()).containsEntry("kafka_offset", "1");
    }

    @Test
    @DisplayName(
            "GIVEN a source on topic tenants with start LATEST WHEN a record is produced after the subscription is active SHOULD emit only that record and stop its consumer thread on dispose")
    void given_latestSourceAndSubscriptionActive_when_recordProducedThenDisposed_should_emitOnlyNewRecordAndStopThread()
            throws Exception {
        PushProperties.Source config = brokerConfig("kafka-tenants", "tenants", "orders:{key}");
        config.getKafka().setStart(PushProperties.KafkaStart.LATEST);
        KafkaPushSource source = new KafkaPushSource("kafka-tenants", config, null, null, new PushDlq(10));
        List<SourcedEvent> received = new CopyOnWriteArrayList<>();

        // Act: subscribe, give the consumer time to get its assignment, then produce.
        Disposable subscription = source.events().subscribe(received::add);
        Thread.sleep(2_000);
        produce("tenants", "late", "{\"id\":\"late\"}");
        awaitTrue(() -> !received.isEmpty(), Duration.ofSeconds(15));
        Thread.sleep(500);
        subscription.dispose();

        // Assert
        assertThat(received).hasSize(1);
        assertThat(received.get(0).key()).isEqualTo("late");
        assertThat(received.get(0).topic()).isEqualTo(Topic.parse("orders:late"));
        awaitTrue(() -> !consumerThreadAlive(TENANTS_CONSUMER_THREAD), Duration.ofSeconds(5));
        assertThat(consumerThreadAlive(TENANTS_CONSUMER_THREAD)).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a template naming the tenant header WHEN a record carries the header or lacks it SHOULD map to the tenant topic or dead-letter the record")
    void given_headerTemplate_when_recordWithAndWithoutHeader_should_mapOrDeadLetter() {
        PushDlq dlq = new PushDlq(10);
        KafkaPushSource source = new KafkaPushSource(
                "kafka-tenant",
                brokerConfig("kafka-tenant", "orders", "tenant:{header.tenant}/orders"),
                null,
                null,
                dlq);
        ConsumerRecord<byte[], byte[]> withHeader = record("o-1", "{\"id\":\"1\"}");
        withHeader.headers().add("tenant", "acme".getBytes(StandardCharsets.UTF_8));

        SourcedEvent mapped = source.map(withHeader);
        SourcedEvent unmapped = source.map(record("o-2", "{\"id\":\"2\"}"));

        assertThat(mapped).isNotNull();
        assertThat(mapped.topic()).isEqualTo(Topic.parse("tenant:acme/orders"));
        assertThat(unmapped).isNull();
        assertThat(dlq.size()).isEqualTo(1);
        PushDlq.Entry entry = dlq.list(null, 10).get(0);
        assertThat(entry.reason()).startsWith("topic:");
        assertThat(entry.key()).isEqualTo("o-2");
    }

    @Test
    @DisplayName("GIVEN a template over the record key WHEN the record has a null key SHOULD dead-letter the record")
    void given_keyTemplate_when_recordHasNullKey_should_deadLetterRecord() {
        PushDlq dlq = new PushDlq(10);
        KafkaPushSource source = new KafkaPushSource(
                "kafka-null-key", brokerConfig("kafka-null-key", "orders", "orders:{key}"), null, null, dlq);

        SourcedEvent event = source.map(record(null, "{\"id\":\"x\"}"));

        assertThat(event).isNull();
        assertThat(dlq.size()).isEqualTo(1);
        assertThat(dlq.list(null, 10).get(0).reason()).startsWith("topic:");
    }

    @Test
    @DisplayName(
            "GIVEN a payload type and PROTO payload format WHEN the value holds an encoded message SHOULD emit a typed event carrying the type URL and the bytes")
    void given_protoPayloadType_when_valueIsEncodedMessage_should_emitTypedEvent() throws Exception {
        Descriptor type = orderEventType();
        byte[] value = orderEvent(type, "42", "paid").toByteArray();
        PushProperties.Source config = brokerConfig("kafka-proto", "orders", "orders:{key}");
        config.setPayloadFormat(PushProperties.PayloadFormat.PROTO);
        KafkaPushSource source = new KafkaPushSource("kafka-proto", config, type, null, new PushDlq(10));

        SourcedEvent event = source.map(new ConsumerRecord<>("orders", 0, 0L, bytes("o-42"), value));

        assertThat(event).isNotNull();
        assertThat(event.typeUrl()).isEqualTo("type.googleapis.com/ev.OrderEvent");
        assertThat(event.proto()).isEqualTo(value);
        assertThat(new String(event.data(), StandardCharsets.UTF_8)).contains("paid");
    }

    @Test
    @DisplayName(
            "GIVEN a payload type and JSON payload format WHEN the value is JSON or not JSON SHOULD emit the same typed event for JSON and dead-letter the rest")
    void given_jsonPayloadType_when_valueIsJsonOrNotJson_should_emitTypedEventOrDeadLetter() throws Exception {
        Descriptor type = orderEventType();
        PushProperties.Source config = brokerConfig("kafka-json", "orders", "orders:{key}");
        config.setPayloadFormat(PushProperties.PayloadFormat.JSON);
        PushDlq dlq = new PushDlq(10);
        KafkaPushSource source = new KafkaPushSource("kafka-json", config, type, null, dlq);

        SourcedEvent event = source.map(record("o-42", "{\"orderId\":\"42\",\"status\":\"paid\"}"));
        SourcedEvent notJson = source.map(record("o-43", "not json"));

        assertThat(event).isNotNull();
        assertThat(event.typeUrl()).isEqualTo("type.googleapis.com/ev.OrderEvent");
        assertThat(new String(event.data(), StandardCharsets.UTF_8)).contains("paid");
        assertThat(notJson).isNull();
        assertThat(dlq.size()).isEqualTo(1);
        assertThat(dlq.list(null, 10).get(0).reason()).startsWith("payload:");
    }

    @Test
    @DisplayName(
            "GIVEN a source configuration that is invalid WHEN the source is constructed SHOULD reject a template variable a record does not offer, a missing bootstrap server and missing topics")
    void given_invalidSourceConfiguration_when_constructed_should_throwIllegalState() {
        PushProperties.Source badTemplate = brokerConfig("kafka-bad", "orders", "orders:{order_id}");
        PushProperties.Source noBootstrap = brokerConfig("kafka-nob", "orders", "orders:{key}");
        noBootstrap.getKafka().setBootstrapServers("");
        PushProperties.Source noTopics = brokerConfig("kafka-notopics", "orders", "orders:{key}");
        noTopics.getKafka().setTopics(List.of());

        assertThatThrownBy(() -> new KafkaPushSource("kafka-bad", badTemplate, null, null, new PushDlq(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("{key}");
        assertThatThrownBy(() -> new KafkaPushSource("kafka-nob", noBootstrap, null, null, new PushDlq(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KafkaPushSource("kafka-notopics", noTopics, null, null, new PushDlq(10)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static PushProperties.Source brokerConfig(String name, String topic, String template) {
        PushProperties.Source config = new PushProperties.Source();
        config.setName(name);
        config.setType(PushProperties.SourceType.KAFKA);
        config.setTopicTemplate(template);
        config.getKafka().setBootstrapServers(bootstrap == null ? "localhost:9092" : bootstrap);
        config.getKafka().setTopics(List.of(topic));
        config.getKafka().setPollTimeout(Duration.ofMillis(200));
        return config;
    }

    private static ConsumerRecord<byte[], byte[]> record(String key, String value) {
        return new ConsumerRecord<>("orders", 0, 0L, key == null ? null : bytes(key), bytes(value));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void produce(String topic, String key, String value) throws Exception {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, bytes(key), bytes(value))).get();
            producer.flush();
        }
    }

    private static boolean consumerThreadAlive(String threadName) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals(threadName));
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    /** A message type with {@code string order_id = 1; string status = 2;} in package {@code ev}. */
    private static Descriptor orderEventType() throws Exception {
        DescriptorProtos.FileDescriptorProto proto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("ev/order_event.proto")
                .setPackage("ev")
                .setSyntax("proto3")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("OrderEvent")
                        .addField(stringField("order_id", 1))
                        .addField(stringField("status", 2)))
                .build();
        FileDescriptor file = FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        return file.findMessageTypeByName("OrderEvent");
    }

    private static DescriptorProtos.FieldDescriptorProto stringField(String name, int number) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                .build();
    }

    private static DynamicMessage orderEvent(Descriptor type, String orderId, String status) {
        return DynamicMessage.newBuilder(type)
                .setField(type.findFieldByName("order_id"), orderId)
                .setField(type.findFieldByName("status"), status)
                .build();
    }
}
