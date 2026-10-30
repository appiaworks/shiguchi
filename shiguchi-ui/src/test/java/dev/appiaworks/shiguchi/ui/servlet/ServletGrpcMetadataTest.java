package dev.appiaworks.shiguchi.ui.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Metadata;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * BDD tests for {@link ServletGrpcMetadata}: the principal, the Authorization header and the
 * forwarded headers reach the call metadata exactly as the reactive populator would send them.
 */
@DisplayName("ServletGrpcMetadata")
class ServletGrpcMetadataTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GIVEN no authentication WHEN building metadata SHOULD send the anonymous principal")
    void given_noAuthentication_when_building_should_sendAnonymousPrincipal() {
        Metadata metadata = new ServletGrpcMetadata(new GatewayProperties()).build(new MockHttpServletRequest());

        assertThat(metadata.get(key("x-principal"))).isEqualTo("anonymous");
        assertThat(metadata.get(key("authorization"))).isNull();
    }

    @Test
    @DisplayName("GIVEN an authenticated principal WHEN building metadata SHOULD send its name")
    void given_authenticatedPrincipal_when_building_should_sendItsName() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "pw"));

        Metadata metadata = new ServletGrpcMetadata(new GatewayProperties()).build(new MockHttpServletRequest());

        assertThat(metadata.get(key("x-principal"))).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN an Authorization header WHEN building metadata SHOULD pass it through")
    void given_authorizationHeader_when_building_should_passItThrough() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc");

        Metadata metadata = new ServletGrpcMetadata(new GatewayProperties()).build(request);

        assertThat(metadata.get(key("authorization"))).isEqualTo("Bearer abc");
    }

    @Test
    @DisplayName("GIVEN configured forward headers WHEN building metadata SHOULD copy the ones the request carries")
    void given_forwardHeaders_when_building_should_copyThoseOnTheRequest() {
        var props = new GatewayProperties();
        props.getClient().setForwardHeaders(List.of("X-Tenant-Id", "x-absent"));
        var request = new MockHttpServletRequest();
        request.addHeader("x-tenant-id", "t-1");

        Metadata metadata = new ServletGrpcMetadata(props).build(request);

        assertThat(metadata.get(key("x-tenant-id"))).isEqualTo("t-1");
        assertThat(metadata.get(key("x-absent"))).isNull();
    }

    @Test
    @DisplayName("GIVEN the tracing headers are forwarded by default WHEN the request has a traceparent SHOULD copy it")
    void given_defaultTracingHeaders_when_requestHasTraceparent_should_copyIt() {
        var request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-abc-def-01");

        Metadata metadata = new ServletGrpcMetadata(new GatewayProperties()).build(request);

        assertThat(metadata.get(key("traceparent"))).isEqualTo("00-abc-def-01");
    }

    @Test
    @DisplayName(
            "GIVEN x-principal and authorization are listed as forward headers WHEN the caller sends them SHOULD NOT copy them")
    void given_gatewaySetHeadersListed_when_callerSendsThem_should_notCopyThem() {
        var props = new GatewayProperties();
        props.getClient().setForwardHeaders(List.of("x-principal", "authorization"));
        var request = new MockHttpServletRequest();
        request.addHeader("x-principal", "mallory");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "pw"));

        Metadata metadata = new ServletGrpcMetadata(props).build(request);

        assertThat(metadata.getAll(key("x-principal"))).containsExactly("alice");
        assertThat(metadata.get(key("authorization"))).isNull();
    }

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}
