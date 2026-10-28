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

@DisplayName("Push actuator endpoint")
class PushEndpointTest {

    private final PushProperties props = new PushProperties();
    private final MemoryPushBroker broker = new MemoryPushBroker();
    private final MemoryPushStore store = new MemoryPushStore();
    private final PushMetrics metrics = new PushMetrics(new SimpleMeterRegistry());
    private final PushConnections connections = new PushConnections();
    private final PushEndpoint endpoint;

    PushEndpointTest() {
        PushProperties.Namespace orders = new PushProperties.Namespace();
        orders.setQos(Qos.AT_LEAST_ONCE);
        orders.setHistorySize(10);
        props.getNamespaces().put("orders", orders);
        props.getNamespaces().put("ticker", new PushProperties.Namespace());
        PushPublisher publisher = new PushPublisher(props, store, broker, metrics);
        PushReport report = new PushReport(props, broker, store, metrics, connections, publisher);
        endpoint = new PushEndpoint(report, props);
    }

    @Test
    @DisplayName("the read overview returns the report's instance, namespace count and path")
    void given_declared_namespaces_when_overview_read_should_return_report_map() {
        assertThat(endpoint.overview())
                .containsKeys("instance", "engine", "namespaces", "path")
                .containsEntry("namespaces", 2)
                .containsEntry("path", "/push");
    }

    @Test
    @DisplayName("the namespaces section responds 200 with a namespaces key")
    void given_namespaces_section_when_read_should_respond_ok_with_namespaces_key() {
        WebEndpointResponse<Object> response =
                endpoint.section("namespaces", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsKey("namespaces");
    }

    @Test
    @DisplayName("the topics section responds 200 with a topics key")
    void given_topics_section_when_read_should_respond_ok_with_topics_key() {
        WebEndpointResponse<Object> response =
                endpoint.section("topics", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsKey("topics");
    }

    @Test
    @DisplayName("the connections section responds 200 with a connections key")
    void given_connections_section_when_read_should_respond_ok_with_connections_key() {
        WebEndpointResponse<Object> response =
                endpoint.section("connections", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsKey("connections");
    }

    @Test
    @DisplayName("an unknown section responds 404")
    void given_unknown_section_when_read_should_respond_not_found() {
        WebEndpointResponse<Object> response =
                endpoint.section("bogus", null, null, null, null, null, null, null, null, null, null, null);

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("with no admin role configured, disconnecting a known connection responds 200 with disconnected=true")
    void given_no_admin_role_and_known_connection_when_disconnected_should_respond_ok() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        WebEndpointResponse<Object> response = endpoint.delete("connections", live.id(), null, context(null));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("disconnected", true);
    }

    @Test
    @DisplayName("disconnecting an unknown connection id responds 404")
    void given_unknown_connection_when_disconnected_should_respond_not_found() {
        WebEndpointResponse<Object> response = endpoint.delete("connections", "missing", null, context(null));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a disconnect under any section other than connections responds 404")
    void given_other_section_when_disconnected_should_respond_not_found() {
        PushConnections.Live live = connections.open("sse", authenticated("alice"));

        WebEndpointResponse<Object> response = endpoint.delete("other", live.id(), null, context(null));

        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a valid test publish responds 200 with the topic and an event id")
    void given_valid_payload_when_published_should_respond_ok_with_topic_and_id() {
        WebEndpointResponse<Object> response =
                endpoint.publish("publish", "orders:1", "{\"a\":1}", context(authenticated("alice")));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getBody())
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("topic", "orders:1")
                .containsKey("id");
    }

    @Test
    @DisplayName("a test publish without data responds 400")
    void given_missing_data_when_published_should_respond_bad_request() {
        WebEndpointResponse<Object> response =
                endpoint.publish("publish", "orders:1", null, context(authenticated("alice")));

        assertThat(response.getStatus()).isEqualTo(400);
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
    static SecurityContext context(Authentication auth) {
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
