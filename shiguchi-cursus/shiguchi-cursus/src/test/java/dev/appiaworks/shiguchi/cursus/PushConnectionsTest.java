package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.test.StepVerifier;

@DisplayName("Push connection registry")
class PushConnectionsTest {

    private final PushConnections connections = new PushConnections();

    @Test
    @DisplayName("an authenticated caller's connection has an id, the principal name and no topics")
    void given_authenticated_caller_when_opened_should_expose_id_principal_and_no_topics() {
        PushConnections.Live live = connections.open("sse", authenticated("alice", "ROLE_PUSH_ADMIN"));

        assertThat(live.id()).isNotBlank();
        PushConnections.Info info = live.info();
        assertThat(info.id()).isEqualTo(live.id());
        assertThat(info.principal()).isEqualTo("alice");
        assertThat(info.transport()).isEqualTo("sse");
        assertThat(info.topics()).isEmpty();
    }

    @Test
    @DisplayName("an anonymous caller's connection has no principal")
    void given_anonymous_caller_when_opened_should_have_null_principal() {
        PushConnections.Live live = connections.open("ws", null);

        assertThat(live.info().principal()).isNull();
    }

    @Test
    @DisplayName("listed connections come back ordered by when they were opened")
    void given_several_connections_when_listed_should_be_sorted_by_since() {
        connections.open("sse", authenticated("alice"));
        connections.open("ws", authenticated("bob"));
        connections.open("sse", null);

        List<PushConnections.Info> listed = connections.list(info -> true);

        assertThat(listed).hasSize(3);
        assertThat(listed).extracting(PushConnections.Info::since).isSorted();
    }

    @Test
    @DisplayName("the size counts every open connection")
    void given_two_open_connections_when_sized_should_count_both() {
        connections.open("sse", authenticated("alice"));
        connections.open("ws", authenticated("bob"));

        assertThat(connections.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a closed connection is no longer listed")
    void given_open_connection_when_closed_should_be_removed_from_the_list() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        connections.close(live);

        assertThat(connections.list(info -> true)).isEmpty();
        assertThat(connections.size()).isZero();
    }

    @Test
    @DisplayName("an admin disconnect completes the closed signal and records the reason")
    void given_open_connection_when_disconnected_by_admin_should_complete_closed_with_reason() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        boolean disconnected = connections.disconnect(live.id(), "admin");

        assertThat(disconnected).isTrue();
        StepVerifier.create(live.closed()).verifyComplete();
        assertThat(live.closeReason()).isEqualTo("admin");
    }

    @Test
    @DisplayName("disconnecting an unknown connection id reports false")
    void given_unknown_id_when_disconnected_should_return_false() {
        connections.open("sse", authenticated("alice"));

        assertThat(connections.disconnect("no-such-connection", "admin")).isFalse();
    }

    @Test
    @DisplayName("a disconnect without a reason records admin as the reason")
    void given_no_reason_when_disconnected_should_record_admin_as_reason() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        connections.disconnect(live.id(), null);

        assertThat(live.closeReason()).isEqualTo("admin");
    }

    @Test
    @DisplayName("topics added to a connection show up as sorted strings in its info")
    void given_topics_added_when_info_read_should_list_them_sorted() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));
        live.topics().add(Topic.parse("orders:2"));
        live.topics().add(Topic.parse("orders:1"));

        assertThat(live.info().topics()).containsExactly("orders:1", "orders:2");
    }

    private static Authentication authenticated(String name, String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(
                name,
                "pwd",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        return token;
    }
}
