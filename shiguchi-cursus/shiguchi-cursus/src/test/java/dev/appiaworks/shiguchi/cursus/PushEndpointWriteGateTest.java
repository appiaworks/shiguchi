package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.Principal;
import java.util.Arrays;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@DisplayName("Push actuator endpoint writes behind the admin role")
class PushEndpointWriteGateTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final PushEndpoint endpoint;

    PushEndpointWriteGateTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        props.getAdmin().setRole("PUSH_ADMIN");
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics);
        PushReport report = new PushReport(props, broker, store, metrics, connections, publisher);
        endpoint = new PushEndpoint(report, props);
    }

    @Test
    @DisplayName("a caller holding ROLE_PUSH_ADMIN may disconnect a connection")
    void given_role_prefixed_authority_when_disconnected_should_respond_ok() {
        PushConnections.Live live = connections.open("sse", authenticated("alice", "ROLE_PUSH_ADMIN"));

        WebEndpointResponse<Object> response =
                endpoint.delete("connections", live.id(), null, context(authenticated("alice", "ROLE_PUSH_ADMIN")));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a caller holding SCOPE_PUSH_ADMIN may disconnect a connection")
    void given_scope_prefixed_authority_when_disconnected_should_respond_ok() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        WebEndpointResponse<Object> response =
                endpoint.delete("connections", live.id(), null, context(authenticated("alice", "SCOPE_PUSH_ADMIN")));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a caller without the admin role is refused with 403 and error forbidden")
    void given_authenticated_caller_without_admin_role_when_disconnected_should_respond_forbidden() {
        PushConnections.Live live = connections.open("sse", authenticated("bob"));

        WebEndpointResponse<Object> response =
                endpoint.delete("connections", live.id(), null, context(authenticated("bob", "ROLE_USER")));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getBody())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("error", "forbidden");
    }

    @Test
    @DisplayName("an anonymous caller is refused with 403 when an admin role is required")
    void given_anonymous_caller_when_disconnected_with_role_required_should_respond_forbidden() {
        PushConnections.Live live = connections.open("sse", authenticated("alice", "ROLE_PUSH_ADMIN"));

        WebEndpointResponse<Object> response = endpoint.delete("connections", live.id(), null, context(null));

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a test publish to an undeclared namespace responds 404")
    void given_undeclared_namespace_when_published_should_respond_not_found() {
        WebEndpointResponse<Object> response =
                endpoint.publish("publish", "nope:x", "1", context(authenticated("alice", "ROLE_PUSH_ADMIN")));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a publish under any section other than publish responds 404")
    void given_other_section_when_published_should_respond_not_found() {
        WebEndpointResponse<Object> response =
                endpoint.publish("other", "orders:1", "1", context(authenticated("alice", "ROLE_PUSH_ADMIN")));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    private static Authentication authenticated(String name, String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(
                name,
                "pwd",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        return token;
    }

    /** A security context whose principal is the given authentication (null for anonymous). */
    private static SecurityContext context(Authentication auth) {
        return new SecurityContext() {
            @Override
            public Principal getPrincipal() {
                return auth;
            }

            @Override
            public boolean isUserInRole(String role) {
                return auth != null
                        && auth.getAuthorities().stream()
                                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
            }
        };
    }
}
