package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.test.StepVerifier;

@DisplayName("Push report writes")
class PushReportWritesTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final PushReport report;

    PushReportWritesTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics);
        report = new PushReport(props, broker, store, metrics, connections, publisher);
    }

    @Test
    @DisplayName("disconnecting a known connection ends it with reason admin")
    void given_known_connection_when_disconnected_should_end_it_with_admin_reason() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        assertThat(report.disconnect(live.id())).isTrue();

        StepVerifier.create(live.closed()).verifyComplete();
        assertThat(live.closeReason()).isEqualTo("admin");
    }

    @Test
    @DisplayName("disconnecting an unknown connection id reports false")
    void given_unknown_connection_when_disconnected_should_return_false() {
        assertThat(report.disconnect("missing")).isFalse();
    }

    @Test
    @DisplayName("a test publish is emitted with from=admin and kept in the topic history")
    void given_declared_namespace_when_published_by_operator_should_emit_and_store_event() {
        StepVerifier.create(report.publish("orders:1", payload(), Map.of()))
                .assertNext(event -> {
                    assertThat(event.id()).isNotBlank();
                    assertThat(event.attributes()).containsEntry("from", "admin");
                })
                .verifyComplete();

        assertThat(store.historyLength(Topic.parse("orders:1"))).isOne();
    }

    @Test
    @DisplayName("a test publish to a malformed topic errors with BAD_REQUEST")
    void given_malformed_topic_when_published_should_error_with_bad_request() {
        StepVerifier.create(report.publish("nope", payload(), Map.of()))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.code()).isEqualTo("bad_request");
                })
                .verify();
    }

    @Test
    @DisplayName("a test publish into an undeclared namespace errors with NOT_FOUND")
    void given_undeclared_namespace_when_published_should_error_with_not_found() {
        StepVerifier.create(report.publish("other:1", payload(), Map.of()))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    PushException ex = (PushException) e;
                    assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.code()).isEqualTo("unknown_namespace");
                })
                .verify();
    }

    private static byte[] payload() {
        return "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    }

    private static Authentication authenticated(String name) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(name, "pwd");
        token.setAuthenticated(true);
        return token;
    }
}
