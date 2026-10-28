package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessage;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult;
import dev.appiaworks.shiguchi.proto.cursus.ConnectDecision;
import dev.appiaworks.shiguchi.proto.cursus.ConnectionInfo;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeDecision;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeHook;
import dev.appiaworks.shiguchi.proto.cursus.TopicActivity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("Push hooks: topic activity and connection notifications")
class PushHooksTest {

    private static final Topic ORDERS_1 = Topic.parse("orders:1");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PushMetrics metrics = new PushMetrics(registry);
    private final PushConnections connections = new PushConnections();
    private final List<Disposable> subscriptions = new ArrayList<>();
    private final List<PushHooks> created = new ArrayList<>();

    @AfterEach
    void tearDown() {
        subscriptions.forEach(Disposable::dispose);
        created.forEach(PushHooks::destroy);
    }

    @Test
    @DisplayName(
            "GIVEN two subscribers of orders:1 WHEN both attach SHOULD notify topic active exactly once, for the first subscriber only")
    void given_twoSubscribersOfOneTopic_when_bothAttach_should_notifyActiveOnce() {
        RecordingHook hook = new RecordingHook(RecordingHook.Failure.NONE);
        PushHooks hooks = hooksWith(Duration.ofMillis(200), hook);

        subscribe(hooks, ORDERS_1);
        subscribe(hooks, ORDERS_1);

        assertThat(hook.activations).hasSize(1);
        TopicActivity activity = hook.activations.get(0);
        assertThat(activity.getTopic()).isEqualTo("orders:1");
        assertThat(activity.getInstance()).isEqualTo("gw-1");
        assertThat(activity.getSubscribers()).isEqualTo(1);
        assertThat(hooks.activeTopics()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN the last subscriber of orders:1 has left WHEN the idle linger passes SHOULD notify topic idle once and forget the topic")
    void given_lastSubscriberLeft_when_idleLingerPasses_should_notifyIdleOnce() throws InterruptedException {
        RecordingHook hook = new RecordingHook(RecordingHook.Failure.NONE);
        PushHooks hooks = hooksWith(Duration.ofMillis(200), hook);
        Disposable first = subscribe(hooks, ORDERS_1);
        Disposable second = subscribe(hooks, ORDERS_1);

        first.dispose();
        second.dispose();

        awaitTrue(() -> hook.idles.size() == 1);
        assertThat(hook.idles).hasSize(1);
        assertThat(hook.idles.get(0).getTopic()).isEqualTo("orders:1");
        assertThat(hook.idles.get(0).getSubscribers()).isZero();
        assertThat(hooks.activeTopics()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN the last subscriber left orders:1 WHEN a new one attaches within the linger SHOULD send neither idle nor a second active")
    void given_resubscribeWithinLinger_when_lingerWouldHaveElapsed_should_notFlap() throws InterruptedException {
        RecordingHook hook = new RecordingHook(RecordingHook.Failure.NONE);
        PushHooks hooks = hooksWith(Duration.ofMillis(200), hook);
        subscribe(hooks, ORDERS_1).dispose();

        subscribe(hooks, ORDERS_1);
        Thread.sleep(400);

        assertThat(hook.idles).isEmpty();
        assertThat(hook.activations).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN an idle linger of zero WHEN the last subscriber leaves SHOULD notify topic idle at once")
    void given_zeroIdleLinger_when_lastSubscriberLeaves_should_notifyIdleImmediately() {
        RecordingHook hook = new RecordingHook(RecordingHook.Failure.NONE);
        PushHooks hooks = hooksWith(Duration.ZERO, hook);
        Disposable subscription = subscribe(hooks, ORDERS_1);

        subscription.dispose();

        assertThat(hook.idles).hasSize(1);
        assertThat(hooks.activeTopics()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a client connection WHEN it opens SHOULD notify connect with its id, transport, instance and no principal")
    void given_clientConnection_when_opens_should_notifyConnectWithItsIdentity() {
        RecordingHook hook = new RecordingHook(RecordingHook.Failure.NONE);
        connections.setHooks(hooksWith(Duration.ofMillis(200), hook));

        PushConnections.Live live = connections.open("sse", null);

        assertThat(hook.connects).hasSize(1);
        ConnectionInfo info = hook.connects.get(0);
        assertThat(info.getId()).isEqualTo(live.id());
        assertThat(info.getTransport()).isEqualTo("sse");
        assertThat(info.getInstance()).isEqualTo("gw-1");
        assertThat(info.getPrincipal()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN two connections WHEN an operator disconnects one and the other simply closes SHOULD report reason admin and reason closed")
    void given_twoConnections_when_oneDisconnectedAndOneCloses_should_reportEachReason() {
        RecordingHook hook = new RecordingHook(RecordingHook.Failure.NONE);
        connections.setHooks(hooksWith(Duration.ofMillis(200), hook));
        PushConnections.Live byOperator = connections.open("ws", null);
        PushConnections.Live byClient = connections.open("sse", null);

        assertThat(connections.disconnect(byOperator.id(), "admin")).isTrue();
        connections.close(byOperator);
        connections.close(byClient);

        assertThat(hook.disconnects).hasSize(2);
        assertThat(hook.disconnects.get(0).getId()).isEqualTo(byOperator.id());
        assertThat(hook.disconnects.get(0).getReason()).isEqualTo("admin");
        assertThat(hook.disconnects.get(1).getId()).isEqualTo(byClient.id());
        assertThat(hook.disconnects.get(1).getReason()).isEqualTo("closed");
    }

    @Test
    @DisplayName(
            "GIVEN a hook that throws and a hook that fails asynchronously WHEN a topic becomes active SHOULD still call the healthy hook and count the failures")
    void given_failingHooks_when_topicActive_should_keepOtherHooksAndCountErrors() {
        RecordingHook thrower = new RecordingHook(RecordingHook.Failure.THROW);
        RecordingHook erroring = new RecordingHook(RecordingHook.Failure.ERROR);
        RecordingHook healthy = new RecordingHook(RecordingHook.Failure.NONE);
        PushHooks hooks = hooksWith(Duration.ofMillis(200), thrower, erroring, healthy);

        subscribe(hooks, ORDERS_1);

        assertThat(healthy.activations).hasSize(1);
        assertThat(registry.get("shiguchi.cursus.hooks")
                        .tag("outcome", "error")
                        .counter()
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("GIVEN no hook registered WHEN a subscriber attaches SHOULD pass the live flux through untracked")
    void given_noHooks_when_subscriberAttaches_should_passFluxThroughUntracked() {
        PushHooks hooks = hooksWith(Duration.ofMillis(200));
        Flux<PushEvent> live = Flux.never();

        Flux<PushEvent> tracked = hooks.track(ORDERS_1, live);

        assertThat(tracked).isSameAs(live);
        assertThat(hooks.activeTopics()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN an instant with seconds and nanos WHEN converted to a protobuf timestamp SHOULD round-trip both")
    void given_instantWithNanos_when_convertedToTimestamp_should_roundTrip() {
        Instant at = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L);

        var timestamp = PushHooks.timestamp(at);

        assertThat(timestamp.getSeconds()).isEqualTo(1_700_000_000L);
        assertThat(timestamp.getNanos()).isEqualTo(123_456_789);
        assertThat(Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()))
                .isEqualTo(at);
    }

    // ---- decisions ----

    @Test
    @DisplayName(
            "GIVEN a hook that rejects the connection and a hook after it WHEN the connect decision is taken SHOULD be forbidden with the reason and never ask the later hook")
    void given_rejectingHook_when_connectDecided_should_forbidWithReasonAndSkipLaterHooks() {
        Deciding rejecting = new Deciding();
        rejecting.connect = c -> Mono.just(
                ConnectDecision.newBuilder().setReject(true).setReason("banned").build());
        Deciding later = new Deciding();
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, rejecting, later);

        StepVerifier.create(hooks.admit(connections.open("sse", null)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class).hasMessage("banned");
                    assertThat(((PushException) e).code()).isEqualTo("forbidden");
                })
                .verify();

        assertThat(later.connectCalls).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN two hooks that return topics to subscribe to WHEN the connect decision is taken SHOULD return the union without duplicates")
    void given_hooksSubscribingTopics_when_connectDecided_should_returnDistinctTopics() {
        Deciding first = new Deciding();
        first.connect = c -> Mono.just(ConnectDecision.newBuilder()
                .addSubscribe("orders:9")
                .addSubscribe("ticker:a")
                .build());
        Deciding second = new Deciding();
        second.connect = c -> Mono.just(ConnectDecision.newBuilder()
                .addSubscribe("orders:9")
                .addSubscribe("ticker:b")
                .build());
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, first, second);

        StepVerifier.create(hooks.admit(connections.open("sse", null)))
                .assertNext(topics -> assertThat(topics)
                        .containsExactly(Topic.parse("orders:9"), Topic.parse("ticker:a"), Topic.parse("ticker:b")))
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN a hook that subscribes the connection to a topic of an undeclared namespace WHEN the connect decision is taken SHOULD fail with unknown_namespace")
    void given_hookNamingUnknownNamespace_when_connectDecided_should_failUnknownNamespace() {
        Deciding hook = new Deciding();
        hook.connect = c ->
                Mono.just(ConnectDecision.newBuilder().addSubscribe("nope:x").build());
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, hook);

        StepVerifier.create(hooks.admit(connections.open("sse", null)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    assertThat(((PushException) e).code()).isEqualTo("unknown_namespace");
                })
                .verify();
    }

    @Test
    @DisplayName(
            "GIVEN two hooks that rewrite the requested topics WHEN the subscribe decision is taken SHOULD compose the rewrites in bean order")
    void given_rewritingHooks_when_subscribeDecided_should_composeInBeanOrder() {
        Deciding first = new Deciding();
        first.subscribe = s -> Mono.just(SubscribeDecision.newBuilder()
                .addTopics(s.getTopics(0).replace("orders:1", "orders:2"))
                .build());
        Deciding second = new Deciding();
        second.subscribe = s -> Mono.just(SubscribeDecision.newBuilder()
                .addTopics(s.getTopics(0).replace("orders:2", "orders:3"))
                .addTopics("ticker:extra")
                .build());
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, first, second);

        StepVerifier.create(hooks.subscribing(connections.open("sse", null), List.of(ORDERS_1)))
                .assertNext(topics ->
                        assertThat(topics).containsExactly(Topic.parse("orders:3"), Topic.parse("ticker:extra")))
                .verifyComplete();

        assertThat(second.subscribeAsked).containsExactly(List.of("orders:2"));
    }

    @Test
    @DisplayName(
            "GIVEN a hook with no opinion and a hook that rejects WHEN the subscribe decision is taken SHOULD be forbidden with the reason")
    void given_rejectingSubscribeHook_when_subscribeDecided_should_forbidWithReason() {
        Deciding silent = new Deciding();
        Deciding rejecting = new Deciding();
        rejecting.subscribe = s -> Mono.just(SubscribeDecision.newBuilder()
                .setReject(true)
                .setReason("not your topic")
                .build());
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, silent, rejecting);

        StepVerifier.create(hooks.subscribing(connections.open("sse", null), List.of(ORDERS_1)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class).hasMessage("not your topic");
                    assertThat(((PushException) e).code()).isEqualTo("forbidden");
                })
                .verify();
    }

    @Test
    @DisplayName(
            "GIVEN a hook that never answers and the default policy WHEN the connect decision is taken SHOULD allow after the timeout and count a timeout")
    void given_slowHook_when_connectDecided_should_allowAndCountTimeout() {
        Deciding slow = new Deciding();
        slow.connect = c -> Mono.never();
        PushHooks hooks = decidingHooks(Duration.ofMillis(50), false, slow);

        StepVerifier.create(hooks.admit(connections.open("sse", null)))
                .assertNext(topics -> assertThat(topics).isEmpty())
                .verifyComplete();

        assertThat(registry.get("shiguchi.cursus.hooks")
                        .tag("hook", "decide_connect")
                        .tag("outcome", "timeout")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName(
            "GIVEN a hook that never answers and hooks.fail-closed WHEN the connect decision is taken SHOULD be forbidden")
    void given_slowHookAndFailClosed_when_connectDecided_should_forbid() {
        Deciding slow = new Deciding();
        slow.connect = c -> Mono.never();
        PushHooks hooks = decidingHooks(Duration.ofMillis(50), true, slow);

        StepVerifier.create(hooks.admit(connections.open("sse", null)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    assertThat(((PushException) e).code()).isEqualTo("forbidden");
                })
                .verify();
    }

    @Test
    @DisplayName(
            "GIVEN a hook that fails WHEN the subscribe decision is taken SHOULD keep the requested topics and count an error; fail-closed forbids instead")
    void given_failingHook_when_subscribeDecided_should_allowUnlessFailClosed() {
        Deciding failing = new Deciding();
        failing.subscribe = s -> Mono.error(new IllegalStateException("boom"));

        StepVerifier.create(decidingHooks(Duration.ofSeconds(2), false, failing)
                        .subscribing(connections.open("sse", null), List.of(ORDERS_1)))
                .assertNext(topics -> assertThat(topics).containsExactly(ORDERS_1))
                .verifyComplete();
        assertThat(registry.get("shiguchi.cursus.hooks")
                        .tag("hook", "decide_subscribe")
                        .tag("outcome", "error")
                        .counter()
                        .count())
                .isEqualTo(1.0);

        StepVerifier.create(decidingHooks(Duration.ofSeconds(2), true, failing)
                        .subscribing(connections.open("sse", null), List.of(ORDERS_1)))
                .expectErrorSatisfies(
                        e -> assertThat(((PushException) e).code()).isEqualTo("forbidden"))
                .verify();
    }

    @Test
    @DisplayName(
            "GIVEN a hook that drops a message and a hook after it WHEN a client message is decided SHOULD answer DROP and not ask the later hook")
    void given_droppingHook_when_clientMessageDecided_should_dropAndSkipLaterHooks() {
        Deciding dropping = new Deciding();
        dropping.message = m -> Mono.just(ClientMessageResult.newBuilder()
                .setAction(ClientMessageResult.Action.DROP)
                .build());
        Deciding later = new Deciding();
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, dropping, later);

        StepVerifier.create(hooks.clientMessage(connections.open("ws", null), ORDERS_1, "{}".getBytes()))
                .assertNext(r -> assertThat(r.getAction()).isEqualTo(ClientMessageResult.Action.DROP))
                .verifyComplete();

        assertThat(later.messageCalls).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a hook that rejects a message WHEN a client message is decided SHOULD answer REJECT with the reason")
    void given_rejectingHook_when_clientMessageDecided_should_rejectWithReason() {
        Deciding rejecting = new Deciding();
        rejecting.message = m -> Mono.just(ClientMessageResult.newBuilder()
                .setAction(ClientMessageResult.Action.REJECT)
                .setReason("spam")
                .build());
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, rejecting);

        StepVerifier.create(hooks.clientMessage(connections.open("ws", null), ORDERS_1, "{}".getBytes()))
                .assertNext(r -> {
                    assertThat(r.getAction()).isEqualTo(ClientMessageResult.Action.REJECT);
                    assertThat(r.getReason()).isEqualTo("spam");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN two hooks that rewrite the payload WHEN a client message is decided SHOULD forward the payload as the last hook left it")
    void given_payloadRewrites_when_clientMessageDecided_should_forwardComposedPayload() {
        Deciding first = new Deciding();
        first.message = m -> Mono.just(ClientMessageResult.newBuilder()
                .setPayload(ByteString.copyFromUtf8(m.getPayload().toStringUtf8() + "A"))
                .build());
        Deciding second = new Deciding();
        second.message = m -> Mono.just(ClientMessageResult.newBuilder()
                .setPayload(ByteString.copyFromUtf8(m.getPayload().toStringUtf8() + "B"))
                .build());
        PushHooks hooks = decidingHooks(Duration.ofSeconds(2), false, first, second);

        StepVerifier.create(hooks.clientMessage(connections.open("ws", null), ORDERS_1, "x".getBytes()))
                .assertNext(r -> {
                    assertThat(r.getAction()).isEqualTo(ClientMessageResult.Action.FORWARD);
                    assertThat(r.getPayload().toStringUtf8()).isEqualTo("xAB");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN no hook registered WHEN any decision is taken SHOULD allow without calling anything and leave topics and payload untouched")
    void given_noHooks_when_decisionsTaken_should_allowUntouched() {
        PushHooks hooks = hooksWith(Duration.ofMillis(200));
        PushConnections.Live live = connections.open("sse", null);

        StepVerifier.create(hooks.admit(live))
                .assertNext(t -> assertThat(t).isEmpty())
                .verifyComplete();
        StepVerifier.create(hooks.subscribing(live, List.of(ORDERS_1)))
                .assertNext(t -> assertThat(t).containsExactly(ORDERS_1))
                .verifyComplete();
        StepVerifier.create(hooks.clientMessage(live, ORDERS_1, "p".getBytes()))
                .assertNext(r -> {
                    assertThat(r.getAction()).isEqualTo(ClientMessageResult.Action.FORWARD);
                    assertThat(r.getPayload().toStringUtf8()).isEqualTo("p");
                })
                .verifyComplete();
    }

    // ---- helpers ----

    private PushHooks decidingHooks(Duration timeout, boolean failClosed, PushHook... hooks) {
        PushProperties properties = new PushProperties();
        properties.getNamespaces().put("orders", new PushProperties.Namespace());
        properties.getNamespaces().put("ticker", new PushProperties.Namespace());
        properties.getHooks().setIdleLinger(Duration.ofMillis(200));
        properties.getHooks().setTimeout(timeout);
        properties.getHooks().setFailClosed(failClosed);
        PushHooks created = new PushHooks(properties, List.of(hooks), metrics, "gw-1");
        this.created.add(created);
        return created;
    }

    /** A hook whose decisions are set per test; every call is counted, no opinion by default. */
    static final class Deciding implements PushHook {
        Function<ConnectionInfo, Mono<ConnectDecision>> connect = c -> Mono.empty();
        Function<SubscribeHook, Mono<SubscribeDecision>> subscribe = s -> Mono.empty();
        Function<ClientMessage, Mono<ClientMessageResult>> message = m -> Mono.empty();
        int connectCalls;
        int messageCalls;
        final List<List<String>> subscribeAsked = new CopyOnWriteArrayList<>();

        @Override
        public Mono<ConnectDecision> decideConnect(ConnectionInfo connection) {
            connectCalls++;
            return connect.apply(connection);
        }

        @Override
        public Mono<SubscribeDecision> decideSubscribe(SubscribeHook subscription) {
            subscribeAsked.add(subscription.getTopicsList());
            return subscribe.apply(subscription);
        }

        @Override
        public Mono<ClientMessageResult> onClientMessage(ClientMessage m) {
            messageCalls++;
            return message.apply(m);
        }
    }

    private PushHooks hooksWith(Duration idleLinger, PushHook... hooks) {
        PushProperties properties = new PushProperties();
        properties.getHooks().setIdleLinger(idleLinger);
        PushHooks created = new PushHooks(properties, List.of(hooks), metrics, "gw-1");
        this.created.add(created);
        return created;
    }

    private Disposable subscribe(PushHooks hooks, Topic topic) {
        Disposable subscription = hooks.track(topic, Flux.never()).subscribe();
        subscriptions.add(subscription);
        return subscription;
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(20);
    }

    /** Records every notification it receives; optionally fails them, synchronously or as a Mono error. */
    static final class RecordingHook implements PushHook {

        enum Failure {
            NONE,
            THROW,
            ERROR
        }

        private final Failure failure;
        final List<TopicActivity> activations = new CopyOnWriteArrayList<>();
        final List<TopicActivity> idles = new CopyOnWriteArrayList<>();
        final List<ConnectionInfo> connects = new CopyOnWriteArrayList<>();
        final List<ConnectionInfo> disconnects = new CopyOnWriteArrayList<>();

        RecordingHook(Failure failure) {
            this.failure = failure;
        }

        @Override
        public Mono<Void> onTopicActive(TopicActivity activity) {
            return record(activations, activity);
        }

        @Override
        public Mono<Void> onTopicIdle(TopicActivity activity) {
            return record(idles, activity);
        }

        @Override
        public Mono<Void> onConnect(ConnectionInfo connection) {
            return record(connects, connection);
        }

        @Override
        public Mono<Void> onDisconnect(ConnectionInfo connection) {
            return record(disconnects, connection);
        }

        private <T> Mono<Void> record(List<T> into, T value) {
            if (failure == Failure.THROW) throw new IllegalStateException("hook threw");
            if (failure == Failure.ERROR) return Mono.error(new IllegalStateException("hook failed"));
            into.add(value);
            return Mono.empty();
        }
    }
}
