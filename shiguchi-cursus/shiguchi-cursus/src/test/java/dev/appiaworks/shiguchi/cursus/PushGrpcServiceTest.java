package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.proto.cursus.PublishRequest;
import dev.appiaworks.shiguchi.proto.cursus.PublishResponse;
import dev.appiaworks.shiguchi.proto.cursus.PublishedEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.Qos;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

@DisplayName("Push gRPC service")
class PushGrpcServiceTest {

    private PushProperties properties;
    private PushMetrics metrics;
    private PushShutdown shutdown;
    private PushConnections connections;
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        properties = new PushProperties();
        properties.getConnection().setMaxLifetime(Duration.ZERO);
        properties.getConnection().setMaxSubscriptions(2);

        PushProperties.Namespace ticker = new PushProperties.Namespace();
        ticker.setPublishAuth(PushProperties.PublishAuth.NONE);
        properties.getNamespaces().put("ticker", ticker);

        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(dev.appiaworks.shiguchi.cursus.Qos.AT_LEAST_ONCE);
        orders.setHistorySize(100);
        orders.setPublishAuth(PushProperties.PublishAuth.NONE);
        properties.getNamespaces().put("orders", orders);

        PushProperties.Namespace inbox = new PushProperties.Namespace();
        inbox.setAuth(PushProperties.SubscribeAuth.AUTHENTICATED);
        properties.getNamespaces().put("inbox", inbox);

        MemoryPushBroker broker = new MemoryPushBroker();
        MemoryPushStore store = new MemoryPushStore();
        metrics = new PushMetrics(new SimpleMeterRegistry());
        PushPublisher publisher = new PushPublisher(properties, store, broker, metrics);
        shutdown = new PushShutdown();
        shutdown.start();
        connections = new PushConnections();

        ReactiveJwtDecoder decoder = token -> {
            if (!"alice-token".equals(token)) return Mono.error(new BadJwtException("bad"));
            // Asynchronous, as a decoder that fetches the signing keys over HTTP is.
            return Mono.just(Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("alice")
                            .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                            .build())
                    .delayElement(Duration.ofMillis(30));
        };

        PushGrpcService service = new PushGrpcService(
                properties,
                broker,
                store,
                publisher,
                new TopicAuthorizer(null),
                metrics,
                shutdown,
                new PushTokenAuthenticator(decoder, null),
                connections,
                null);

        String name = "push-grpc-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name)
                .addService(service)
                .intercept(new PushGrpcHeaders())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    // ---- publish ----

    @Test
    @DisplayName("publishing a JSON payload to a topic returns the event with its id")
    void given_json_payload_when_published_should_return_event_with_id() throws Exception {
        PublishResponse response = blocking().publish(publishRequest("{\"n\":1}", "ticker:btc"));

        assertThat(response.getEventsList()).hasSize(1);
        PublishedEvent event = response.getEvents(0);
        assertThat(event.getTopic()).isEqualTo("ticker:btc");
        assertThat(event.getId()).isNotEmpty();
        assertThat(metrics.publishedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("publishing to two topics returns two events in request order")
    void given_two_topics_when_published_should_return_events_in_request_order() throws Exception {
        PublishResponse response = blocking().publish(publishRequest("{\"n\":1}", "ticker:a", "ticker:b"));

        assertThat(response.getEventsList())
                .extracting(PublishedEvent::getTopic)
                .containsExactly("ticker:a", "ticker:b");
    }

    @Test
    @DisplayName("publishing without a payload is rejected as invalid argument")
    void given_no_payload_when_published_should_fail_invalid_argument() {
        PublishRequest request =
                PublishRequest.newBuilder().addTopics("ticker:btc").build();

        assertThatThrownBy(() -> blocking().publish(request))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                    assertThat(e.getStatus().getDescription()).contains("payload is required");
                });
    }

    @Test
    @DisplayName("publishing to an undeclared namespace is rejected as not found")
    void given_unknown_namespace_when_published_should_fail_not_found() {
        assertThatThrownBy(() -> blocking().publish(publishRequest("{\"n\":1}", "nope:x")))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    @DisplayName("publishing to an authenticated namespace needs a valid bearer token")
    void given_authenticated_namespace_when_published_should_require_valid_token() throws Exception {
        PublishRequest request = publishRequest("{\"n\":1}", "inbox:alice");

        assertThatThrownBy(() -> blocking().publish(request))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        assertThatThrownBy(() -> blocking("Bearer wrong").publish(request))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        PublishResponse response = blocking("Bearer alice-token").publish(request);
        assertThat(response.getEventsList()).hasSize(1);
    }

    // ---- subscribe ----

    @Test
    @DisplayName("a subscriber receives a published event with its payload, attributes, key and time")
    void given_subscriber_when_event_published_should_receive_full_event() throws Exception {
        Subscription sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("ticker:btc").build());
        awaitTrue(() -> metrics.connections("grpc") == 1);

        PublishRequest request = PublishRequest.newBuilder()
                .addTopics("ticker:btc")
                .setJson(jsonValue("{\"n\":1}"))
                .putAttributes("a", "b")
                .setKey("k")
                .build();
        blocking().publish(request);
        awaitTrue(() -> sub.events.size() == 1);

        PushEvent event = sub.events.get(0);
        assertThat(event.getTopic()).isEqualTo("ticker:btc");
        assertThat(event.getId()).isNotEmpty();
        assertThat(event.getJson().getStructValue().getFieldsOrThrow("n").getNumberValue())
                .isEqualTo(1.0);
        assertThat(event.getAttributesMap()).containsEntry("a", "b");
        assertThat(event.getKey()).isEqualTo("k");
        assertThat(event.getTime().getSeconds()).isGreaterThan(0);
        assertThat(metrics.deliveredCount()).isEqualTo(1);
        List<PushConnections.Info> open = connections.list(i -> true);
        assertThat(open).hasSize(1);
        assertThat(connections.size()).isEqualTo(1);
        assertThat(open.get(0).transport()).isEqualTo("grpc");
    }

    @Test
    @DisplayName(
            "a subscriber whose token is decoded asynchronously is served, not failed for registering its handlers late")
    void given_token_decoded_asynchronously_when_subscribing_should_stream_events() throws Exception {
        Metadata metadata = new Metadata();
        metadata.put(PushGrpcHeaders.AUTHORIZATION, "Bearer alice-token");
        Subscription sub = new Subscription();
        PushServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .subscribe(
                        SubscribeRequest.newBuilder().addTopics("inbox:alice").build(), sub);
        awaitTrue(() -> metrics.connections("grpc") == 1);

        blocking("Bearer alice-token").publish(publishRequest("{\"n\":1}", "inbox:alice"));
        awaitTrue(() -> sub.events.size() == 1);

        assertThat(sub.error.get()).isNull();
        assertThat(sub.events.get(0).getTopic()).isEqualTo("inbox:alice");
    }

    @Test
    @DisplayName("subscribing to an authenticated namespace without a token fails unauthenticated")
    void given_no_token_when_subscribing_to_authenticated_namespace_should_fail_unauthenticated() throws Exception {
        Subscription sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("inbox:alice").build());

        awaitTrue(() -> sub.error.get() != null);

        assertThat(Status.fromThrowable(sub.error.get()).getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("subscribing with an invalid topic name fails invalid argument")
    void given_invalid_topic_when_subscribing_should_fail_invalid_argument() throws Exception {
        Subscription sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("bad topic").build());

        awaitTrue(() -> sub.error.get() != null);

        assertThat(Status.fromThrowable(sub.error.get()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("subscribing to more topics than the connection allows fails invalid argument")
    void given_too_many_topics_when_subscribing_should_fail_invalid_argument() throws Exception {
        Subscription sub = subscribe(SubscribeRequest.newBuilder()
                .addTopics("ticker:a")
                .addTopics("ticker:b")
                .addTopics("ticker:c")
                .build());

        awaitTrue(() -> sub.error.get() != null);

        Status status = Status.fromThrowable(sub.error.get());
        assertThat(status.getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(status.getDescription()).contains("at most 2");
    }

    // ---- resume ----

    @Test
    @DisplayName("resuming after an event id replays the later events and then continues live")
    void given_history_when_resuming_after_first_id_should_replay_later_events_then_live() throws Exception {
        PublishResponse published = blocking().publish(publishRequest("{\"n\":1}", "orders:1"));
        String first = published.getEvents(0).getId();
        String second = blocking()
                .publish(publishRequest("{\"n\":2}", "orders:1"))
                .getEvents(0)
                .getId();
        String third = blocking()
                .publish(publishRequest("{\"n\":3}", "orders:1"))
                .getEvents(0)
                .getId();

        Subscription sub = subscribe(SubscribeRequest.newBuilder()
                .addTopics("orders:1")
                .setAfter(first)
                .build());
        awaitTrue(() -> sub.events.size() == 2);
        Thread.sleep(200);

        assertThat(sub.events).extracting(PushEvent::getId).containsExactly(second, third);

        String fourth = blocking()
                .publish(publishRequest("{\"n\":4}", "orders:1"))
                .getEvents(0)
                .getId();
        awaitTrue(() -> sub.events.size() == 3);
        assertThat(sub.events.get(2).getId()).isEqualTo(fourth);
    }

    @Test
    @DisplayName("resuming after an id that is not an event id fails invalid argument")
    void given_garbage_after_id_when_subscribing_should_fail_invalid_argument() throws Exception {
        Subscription sub = subscribe(SubscribeRequest.newBuilder()
                .addTopics("orders:1")
                .setAfter("garbage")
                .build());

        awaitTrue(() -> sub.error.get() != null);

        assertThat(Status.fromThrowable(sub.error.get()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName(
            "asking for at-most-once on a history namespace does not replay, says no_history, and live events arrive")
    void given_at_most_once_override_when_resuming_should_not_replay_but_deliver_live() throws Exception {
        String first = blocking()
                .publish(publishRequest("{\"n\":1}", "orders:1"))
                .getEvents(0)
                .getId();
        blocking().publish(publishRequest("{\"n\":2}", "orders:1"));

        Subscription sub = subscribe(SubscribeRequest.newBuilder()
                .addTopics("orders:1")
                .setAfter(first)
                .setQos(Qos.AT_MOST_ONCE)
                .build());
        awaitTrue(() -> metrics.connections("grpc") == 1);
        Thread.sleep(300);
        // Nothing is replayed, but the resume id cannot be honoured: the client is told once.
        assertThat(sub.events).hasSize(1);
        assertThat(sub.events.get(0).getAttributesMap())
                .containsEntry("push.signal", "reset")
                .containsEntry("reason", "no_history");

        String fresh = blocking()
                .publish(publishRequest("{\"n\":3}", "orders:1"))
                .getEvents(0)
                .getId();
        awaitTrue(() -> sub.events.size() == 2);
        assertThat(sub.events.get(1).getId()).isEqualTo(fresh);
    }

    // ---- closing ----

    @Test
    @DisplayName("an operator disconnect ends the stream unavailable with the admin close reason")
    void given_open_stream_when_admin_disconnects_should_end_unavailable_with_admin_reason() throws Exception {
        Subscription sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("ticker:btc").build());
        awaitTrue(() -> metrics.connections("grpc") == 1);
        String id = connections.list(i -> true).get(0).id();

        assertThat(connections.disconnect(id, "admin")).isTrue();
        awaitTrue(() -> sub.error.get() != null);

        Throwable error = sub.error.get();
        assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(Status.trailersFromThrowable(error).get(PushEventProtos.CLOSE_REASON))
                .isEqualTo("admin");
        awaitTrue(() -> metrics.connections("grpc") == 0 && connections.size() == 0);
    }

    @Test
    @DisplayName("stopping the instance ends open streams unavailable with the shutdown close reason")
    void given_open_stream_when_instance_stops_should_end_unavailable_with_shutdown_reason() throws Exception {
        Subscription sub =
                subscribe(SubscribeRequest.newBuilder().addTopics("ticker:btc").build());
        awaitTrue(() -> metrics.connections("grpc") == 1);

        shutdown.stop();
        awaitTrue(() -> sub.error.get() != null);

        Throwable error = sub.error.get();
        assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(Status.trailersFromThrowable(error).get(PushEventProtos.CLOSE_REASON))
                .isEqualTo("shutdown");
    }

    @Test
    @DisplayName("a client that cancels its call is removed from the connection count")
    void given_open_stream_when_client_cancels_should_release_connection() throws Exception {
        Subscription sub = new Subscription();
        Context.CancellableContext ctx = Context.current().withCancellation();
        ctx.run(() -> PushServiceGrpc.newStub(channel)
                .subscribe(SubscribeRequest.newBuilder().addTopics("ticker:btc").build(), sub));
        awaitTrue(() -> metrics.connections("grpc") == 1);

        ctx.cancel(null);

        awaitTrue(() -> metrics.connections("grpc") == 0 && connections.size() == 0);
        assertThat(metrics.connections("grpc")).isZero();
        assertThat(connections.size()).isZero();
    }

    // ---- helpers ----

    private PushServiceGrpc.PushServiceBlockingStub blocking() {
        return PushServiceGrpc.newBlockingStub(channel);
    }

    private PushServiceGrpc.PushServiceBlockingStub blocking(String authorization) {
        Metadata metadata = new Metadata();
        metadata.put(PushGrpcHeaders.AUTHORIZATION, authorization);
        return blocking().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static Value jsonValue(String json) throws Exception {
        Value.Builder builder = Value.newBuilder();
        JsonFormat.parser().merge(json, builder);
        return builder.build();
    }

    private static PublishRequest publishRequest(String json, String... topics) throws Exception {
        PublishRequest.Builder builder = PublishRequest.newBuilder().setJson(jsonValue(json));
        for (String topic : topics) builder.addTopics(topic);
        return builder.build();
    }

    private Subscription subscribe(SubscribeRequest request) {
        Subscription sub = new Subscription();
        PushServiceGrpc.newStub(channel).subscribe(request, sub);
        return sub;
    }

    /** What an asynchronous subscriber has seen so far. */
    private static final class Subscription implements StreamObserver<PushEvent> {
        final List<PushEvent> events = new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void onNext(PushEvent value) {
            events.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
        }

        @Override
        public void onCompleted() {
            completed.set(true);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within 10 s");
            Thread.sleep(10);
        }
    }
}
