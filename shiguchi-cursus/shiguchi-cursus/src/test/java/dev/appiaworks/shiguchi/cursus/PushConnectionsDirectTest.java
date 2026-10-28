package dev.appiaworks.shiguchi.cursus;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("PushConnections direct delivery")
class PushConnectionsDirectTest {

    private static final Duration GENEROUS = Duration.ofSeconds(3);
    private static final PushSender.Status DELIVERED = PushSender.Status.DELIVERED;
    private static final PushSender.Status REJECTED = PushSender.Status.REJECTED;

    private final PushConnections connections = new PushConnections();

    private static PushEvent event(PushConnections.Live live, String id) {
        return new PushEvent(
                Topic.parse("_direct:" + live.id()),
                id,
                "{}".getBytes(UTF_8),
                Map.of(PushConnections.DIRECT, "true"),
                null,
                Instant.now());
    }

    private static Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    @DisplayName(
            "GIVEN a consumer that writes each direct event WHEN deliverDirect without ack SHOULD report DELIVERED")
    void given_consumer_that_writes_when_deliverDirect_without_ack_should_report_delivered() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);
        live.direct().subscribe(live::delivered);

        // Act
        PushSender.Status status =
                live.deliverDirect(event(live, "evt-1"), false, GENEROUS).block(GENEROUS);

        // Assert
        assertThat(status).isEqualTo(DELIVERED);
    }

    @Test
    @DisplayName("GIVEN a written event that nobody acknowledges WHEN deliverDirect requires ack SHOULD time out")
    void given_written_event_without_ack_when_deliverDirect_requires_ack_should_time_out() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);
        live.direct().subscribe(live::delivered);

        // Act
        PushSender.Status status = live.deliverDirect(event(live, "evt-1"), true, Duration.ofMillis(200))
                .block(GENEROUS);

        // Assert
        assertThat(status).isEqualTo(PushSender.Status.TIMEOUT);
    }

    @Test
    @DisplayName(
            "GIVEN an anonymous connection that acknowledges the event WHEN deliverDirect requires ack SHOULD report DELIVERED")
    void given_anonymous_connection_that_acknowledges_when_deliverDirect_requires_ack_should_report_delivered() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);
        live.direct().subscribe(live::delivered);
        Mono<PushSender.Status> outcome = live.deliverDirect(event(live, "evt-1"), true, GENEROUS);

        // Act: the consumer has already written the event, so the client can acknowledge it
        boolean acknowledged = connections.acknowledgeDirect(live.id(), "evt-1", null);

        // Assert
        assertThat(acknowledged).isTrue();
        assertThat(outcome.block(GENEROUS)).isEqualTo(DELIVERED);
    }

    @Test
    @DisplayName(
            "GIVEN a connection of user ann WHEN another principal or ann acknowledges SHOULD refuse bob and accept ann")
    void given_connection_of_ann_when_acknowledged_by_bob_then_by_ann_should_refuse_bob_and_accept_ann() {
        // Arrange
        PushConnections.Live live = connections.open("ws", user("ann"));
        live.direct().subscribe(live::delivered);
        live.deliverDirect(event(live, "evt-1"), true, GENEROUS);

        // Act
        boolean byBob = connections.acknowledgeDirect(live.id(), "evt-1", user("bob"));
        boolean byAnn = connections.acknowledgeDirect(live.id(), "evt-1", user("ann"));

        // Assert
        assertThat(byBob).isFalse();
        assertThat(byAnn).isTrue();
    }

    @Test
    @DisplayName("GIVEN an SSE connection WHEN deliverDirect requires ack SHOULD report REJECTED immediately")
    void given_sse_connection_when_deliverDirect_requires_ack_should_report_rejected_immediately() {
        // Arrange
        PushConnections.Live live = connections.open("sse", null);

        // Act
        Mono<PushSender.Status> outcome = live.deliverDirect(event(live, "evt-1"), true, GENEROUS);

        // Assert
        StepVerifier.create(outcome).expectNext(REJECTED).verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a direct queue nobody drains WHEN one more event is sent SHOULD report REJECTED")
    void given_direct_queue_nobody_drains_when_one_more_event_is_sent_should_report_rejected() {
        // Arrange: fill the queue; the earlier deliveries stay pending and are not awaited
        PushConnections.Live live = connections.open("ws", null);
        for (int i = 0; i < PushConnections.DIRECT_QUEUE; i++) {
            live.deliverDirect(event(live, "evt-" + i), false, Duration.ofMillis(200));
        }

        // Act
        PushSender.Status overflow = live.deliverDirect(event(live, "evt-overflow"), false, Duration.ofMillis(200))
                .block(GENEROUS);

        // Assert
        assertThat(overflow).isEqualTo(REJECTED);
    }

    @Test
    @DisplayName(
            "GIVEN a connection closed while an event waits unwritten WHEN deliverDirect is pending SHOULD report REJECTED")
    void given_connection_closed_while_event_waits_when_deliverDirect_is_pending_should_report_rejected() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);
        Mono<PushSender.Status> outcome = live.deliverDirect(event(live, "evt-1"), false, GENEROUS);

        // Act
        connections.close(live);

        // Assert
        assertThat(outcome.block(GENEROUS)).isEqualTo(REJECTED);
    }
}
