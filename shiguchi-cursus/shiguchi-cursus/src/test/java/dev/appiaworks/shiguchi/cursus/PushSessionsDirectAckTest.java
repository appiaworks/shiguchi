package dev.appiaworks.shiguchi.cursus;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("PushSessions acknowledgement of a direct event")
class PushSessionsDirectAckTest {

    private static final Duration GENEROUS = Duration.ofSeconds(3);

    private final PushProperties properties = new PushProperties();
    private final PushConnections connections = new PushConnections();
    private final TopicAuthorizer authorizer = new TopicAuthorizer(null);
    private PushSessions sessions;

    @BeforeEach
    void setUp() {
        sessions = new PushSessions(properties, new MemoryPushStore(), new PushMetrics(new SimpleMeterRegistry()));
        sessions.setDirect(connections, "_direct");
    }

    private static PushEvent event(PushConnections.Live live, String id) {
        return new PushEvent(
                Topic.parse("_direct:" + live.id()),
                id,
                "{}".getBytes(UTF_8),
                Map.of(PushConnections.DIRECT, "true"),
                null,
                Instant.now());
    }

    @Test
    @DisplayName(
            "GIVEN a written direct event waiting on an anonymous connection WHEN the client acks it SHOULD complete empty")
    void given_written_direct_event_on_anonymous_connection_when_client_acks_should_complete_empty() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);
        live.direct().subscribe(live::delivered);
        Mono<PushSender.Status> outcome = live.deliverDirect(event(live, "evt-1"), true, GENEROUS);

        // Act and Assert
        StepVerifier.create(sessions.ack(null, null, "_direct:" + live.id(), "evt-1", authorizer))
                .verifyComplete();
        assertThat(outcome.block(GENEROUS)).isEqualTo(PushSender.Status.DELIVERED);
    }

    @Test
    @DisplayName("GIVEN no direct event pending on the connection WHEN the client acks an event SHOULD fail not_found")
    void given_no_direct_event_pending_when_client_acks_should_fail_not_found() {
        // Arrange
        PushConnections.Live live = connections.open("ws", null);

        // Act and Assert
        StepVerifier.create(sessions.ack(null, null, "_direct:" + live.id(), "evt-1", authorizer))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOfSatisfying(PushException.class, p -> {
                    assertThat(p.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(p.code()).isEqualTo("not_found");
                }))
                .verify(GENEROUS);
    }
}
