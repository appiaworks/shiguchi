package dev.appiaworks.shiguchi.cursus;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("PushSender targeted Send")
class PushSenderTest {

    private static final String NS = "_direct";
    private static final Duration GENEROUS = Duration.ofSeconds(3);

    private final PushConnections connections = new PushConnections();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private PushSender sender;

    @BeforeEach
    void setUp() {
        sender = senderWith(properties(PushProperties.PublishAuth.NONE));
    }

    private static PushProperties properties(PushProperties.PublishAuth publishAuth) {
        PushProperties.Namespace direct = new PushProperties.Namespace();
        direct.setPublishAuth(publishAuth);
        PushProperties properties = new PushProperties();
        properties.getNamespaces().put(NS, direct);
        return properties;
    }

    private PushSender senderWith(PushProperties properties) {
        return new PushSender(properties, connections, null, new TopicAuthorizer(null), metrics);
    }

    private static PushEventProtos.Payload payload(String json) {
        return new PushEventProtos.Payload(json.getBytes(UTF_8), null, null);
    }

    private static Authentication user(String name) {
        return new UsernamePasswordAuthenticationToken(name, "pw", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    /** Subscribes as a transport would: records each direct event and reports it written. */
    private static List<PushEvent> consume(PushConnections.Live live) {
        List<PushEvent> received = new CopyOnWriteArrayList<>();
        live.direct().subscribe(event -> {
            received.add(event);
            live.delivered(event);
        });
        return received;
    }

    private PushSender.Result send(PushSender target, PushSender.Target to, String json) {
        return target.send(null, to, payload(json), Map.of(), false, GENEROUS).block(GENEROUS);
    }

    @Test
    @DisplayName(
            "GIVEN two connections of ann and one of bob WHEN Send targets ann SHOULD deliver to both of ann's connections only")
    void given_two_connections_of_ann_when_send_targets_ann_should_deliver_to_both_of_anns_connections() {
        // Arrange
        PushConnections.Live first = connections.open("ws", user("ann"));
        PushConnections.Live second = connections.open("sse", user("ann"));
        PushConnections.Live bob = connections.open("ws", user("bob"));
        List<PushEvent> firstSeen = consume(first);
        List<PushEvent> secondSeen = consume(second);
        List<PushEvent> bobSeen = consume(bob);

        // Act
        PushSender.Result result = send(sender, PushSender.Target.ofPrincipal("ann"), "{\"a\":1}");

        // Assert
        assertThat(result.deliveries())
                .extracting(PushSender.Delivery::connectionId)
                .containsExactlyInAnyOrder(first.id(), second.id());
        assertThat(result.deliveries())
                .extracting(PushSender.Delivery::status)
                .containsOnly(PushSender.Status.DELIVERED);
        assertThat(firstSeen).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(result.id());
            assertThat(e.topic().namespace()).isEqualTo(NS);
            assertThat(e.topic().name()).isEqualTo(first.id());
            assertThat(e.attributes()).containsEntry(PushConnections.DIRECT, "true");
        });
        assertThat(secondSeen).singleElement().satisfies(e -> assertThat(e.id()).isEqualTo(result.id()));
        assertThat(bobSeen).isEmpty();
    }

    @Test
    @DisplayName("GIVEN two connections WHEN Send targets one connection id SHOULD deliver only to that connection")
    void given_two_connections_when_send_targets_one_connection_id_should_deliver_only_to_that_connection() {
        // Arrange
        PushConnections.Live target = connections.open("ws", null);
        PushConnections.Live bystander = connections.open("ws", null);
        List<PushEvent> bystanderSeen = consume(bystander);
        consume(target);

        // Act
        PushSender.Result result = send(sender, PushSender.Target.ofConnection(target.id()), "{}");

        // Assert
        assertThat(result.deliveries())
                .extracting(PushSender.Delivery::connectionId)
                .containsExactly(target.id());
        assertThat(bystanderSeen).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN two devices of ann WHEN Send targets one device SHOULD deliver only to the connection of that device")
    void given_two_devices_of_ann_when_send_targets_one_device_should_deliver_only_to_that_device() {
        // Arrange
        PushConnections.Live phone = connections.open("ws", user("ann"));
        phone.deviceId("phone-1");
        PushConnections.Live laptop = connections.open("ws", user("ann"));
        laptop.deviceId("laptop-1");
        consume(phone);
        List<PushEvent> laptopSeen = consume(laptop);

        // Act
        PushSender.Result result = send(sender, PushSender.Target.ofDevice("ann", "phone-1"), "{}");

        // Assert
        assertThat(result.deliveries())
                .extracting(PushSender.Delivery::connectionId)
                .containsExactly(phone.id());
        assertThat(laptopSeen).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN no connection of nobody WHEN Send targets nobody SHOULD answer one NOT_CONNECTED entry with no connection id")
    void given_no_connection_of_nobody_when_send_targets_nobody_should_answer_one_not_connected_entry() {
        // Act
        PushSender.Result result = send(sender, PushSender.Target.ofPrincipal("nobody"), "{}");

        // Assert
        assertThat(result.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.connectionId()).isEmpty();
            assertThat(d.status()).isEqualTo(PushSender.Status.NOT_CONNECTED);
        });
    }

    @Test
    @DisplayName("GIVEN the send namespace is not declared WHEN Send is attempted SHOULD refuse with a bad request")
    void given_send_namespace_not_declared_when_send_is_attempted_should_refuse_with_bad_request() {
        // Arrange
        PushSender undeclared = senderWith(new PushProperties());

        // Act and Assert
        assertThatThrownBy(() -> send(undeclared, PushSender.Target.ofPrincipal("ann"), "{}"))
                .isInstanceOfSatisfying(PushException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("targeted Send is off");
                });
    }

    @Test
    @DisplayName(
            "GIVEN publish-auth AUTHENTICATED and no caller WHEN Send is attempted SHOULD refuse as unauthenticated")
    void given_publish_auth_authenticated_and_no_caller_when_send_is_attempted_should_refuse_as_unauthenticated() {
        // Arrange
        PushSender guarded = senderWith(properties(PushProperties.PublishAuth.AUTHENTICATED));

        // Act and Assert
        assertThatThrownBy(() -> send(guarded, PushSender.Target.ofPrincipal("ann"), "{}"))
                .isInstanceOfSatisfying(PushException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(e.code()).isEqualTo("unauthenticated");
                });
    }

    @Test
    @DisplayName("GIVEN a payload larger than max-message-bytes WHEN Send is attempted SHOULD refuse as too large")
    void given_payload_larger_than_max_message_bytes_when_send_is_attempted_should_refuse_as_too_large() {
        // Arrange
        PushProperties properties = properties(PushProperties.PublishAuth.NONE);
        properties.getConnection().setMaxMessageBytes(10);
        PushSender limited = senderWith(properties);

        // Act and Assert
        assertThatThrownBy(() -> send(limited, PushSender.Target.ofPrincipal("ann"), "{\"a\":\"0123456789\"}"))
                .isInstanceOfSatisfying(
                        PushException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    }
}
