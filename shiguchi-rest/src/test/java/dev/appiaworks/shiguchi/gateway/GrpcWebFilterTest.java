package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Unit tests for gRPC-Web static helper methods in {@link GrpcWebFilter}.
 */
@DisplayName("gRPC-Web filter helpers")
class GrpcWebFilterTest {

    @Test
    @DisplayName("GIVEN a timeout string with minutes WHEN parsed SHOULD return Duration in minutes")
    void given_timeoutMinutes_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("100m");

        assertThat(duration).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("GIVEN a timeout string with seconds WHEN parsed SHOULD return Duration in seconds")
    void given_timeoutSeconds_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("5S");

        assertThat(duration).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN a timeout string with hours WHEN parsed SHOULD return Duration in hours")
    void given_timeoutHours_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("2H");

        assertThat(duration).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("GIVEN a timeout string with microseconds WHEN parsed SHOULD return Duration in nanos")
    void given_timeoutMicros_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("30u");

        assertThat(duration).isEqualTo(Duration.ofNanos(30_000));
    }

    @Test
    @DisplayName("GIVEN an invalid timeout unit WHEN parsed SHOULD return null")
    void given_invalidTimeoutUnit_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout("100x");

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a non-numeric timeout value WHEN parsed SHOULD return null")
    void given_nonNumericTimeout_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout("abcm");

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a null timeout value WHEN parsed SHOULD return null")
    void given_nullTimeout_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout(null);

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a malformed timeout string WHEN parsed SHOULD return null")
    void given_malformedTimeout_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout("invalid");

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a method path with package.Service/Method and no prefix WHEN parsed SHOULD split into parts")
    void given_methodPathWithNoPrefix_when_parsed_should_splitParts() {
        String[] parts = GrpcWebFilter.methodPath("/pkg.Svc/Method", "");

        assertThat(parts).hasSize(2).containsExactly("pkg.Svc", "Method");
    }

    @Test
    @DisplayName("GIVEN a method path with prefix WHEN parsed SHOULD strip prefix and split")
    void given_methodPathWithPrefix_when_parsed_should_stripAndSplit() {
        String[] parts = GrpcWebFilter.methodPath("/grpc-web/pkg.Svc/Method", "/grpc-web");

        assertThat(parts).hasSize(2).containsExactly("pkg.Svc", "Method");
    }

    @Test
    @DisplayName("GIVEN a method path with three segments (not two) WHEN parsed SHOULD return null")
    void given_methodPathThreeSegments_when_parsed_should_returnNull() {
        String[] parts = GrpcWebFilter.methodPath("/pkg.Svc/Method/Extra", "");

        assertThat(parts).isNull();
    }

    @Test
    @DisplayName("GIVEN a method path not starting with prefix WHEN parsed SHOULD return null")
    void given_methodPathMissingPrefix_when_parsed_should_returnNull() {
        String[] parts = GrpcWebFilter.methodPath("/pkg.Svc/Method", "/grpc-web");

        assertThat(parts).isNull();
    }

    @Test
    @DisplayName("GIVEN an empty service or method segment WHEN parsed SHOULD return null")
    void given_emptySegment_when_parsed_should_returnNull() {
        String[] parts = GrpcWebFilter.methodPath("//Method", "");

        assertThat(parts).isNull();
    }

    @Test
    @DisplayName("GIVEN a prefix with trailing slashes WHEN normalized SHOULD remove trailing slashes")
    void given_prefixWithTrailingSlashes_when_normalized_should_removeSlashes() {
        String normalized = GrpcWebFilter.normalizePrefix("/grpc-web/");

        assertThat(normalized).isEqualTo("/grpc-web");
    }

    @Test
    @DisplayName("GIVEN a prefix with multiple trailing slashes WHEN normalized SHOULD remove all")
    void given_prefixWithMultipleSlashes_when_normalized_should_removeAll() {
        String normalized = GrpcWebFilter.normalizePrefix("/grpc-web///");

        assertThat(normalized).isEqualTo("/grpc-web");
    }

    @Test
    @DisplayName("GIVEN a null prefix WHEN normalized SHOULD return empty string")
    void given_nullPrefix_when_normalized_should_returnEmpty() {
        String normalized = GrpcWebFilter.normalizePrefix(null);

        assertThat(normalized).isEmpty();
    }

    @Test
    @DisplayName("GIVEN application/grpc-web+proto content type WHEN checked SHOULD return true")
    void given_grpcWebProtoContentType_when_checked_should_returnTrue() {
        MediaType contentType = MediaType.parseMediaType("application/grpc-web+proto");

        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(contentType);

        assertThat(isGrpcWeb).isTrue();
    }

    @Test
    @DisplayName("GIVEN application/grpc-web-text+proto content type WHEN checked SHOULD return true")
    void given_grpcWebTextProtoContentType_when_checked_should_returnTrue() {
        MediaType contentType = MediaType.parseMediaType("application/grpc-web-text+proto");

        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(contentType);

        assertThat(isGrpcWeb).isTrue();
    }

    @Test
    @DisplayName("GIVEN application/json content type WHEN checked SHOULD return false")
    void given_jsonContentType_when_checked_should_returnFalse() {
        MediaType contentType = MediaType.APPLICATION_JSON;

        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(contentType);

        assertThat(isGrpcWeb).isFalse();
    }

    @Test
    @DisplayName("GIVEN null content type WHEN checked SHOULD return false")
    void given_nullContentType_when_checked_should_returnFalse() {
        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(null);

        assertThat(isGrpcWeb).isFalse();
    }

    private static MockServerWebExchange grpcWebCall(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .contentType(MediaType.parseMediaType("application/grpc-web+proto"))
                .body(new String(new char[] {0, 0, 0, 0, 0})));
    }

    @Test
    @DisplayName("GIVEN a method hidden by expose-tags WHEN called over gRPC-Web SHOULD answer UNIMPLEMENTED "
            + "and never reach the next filter")
    void GIVEN_hiddenMethod_WHEN_calledOverGrpcWeb_SHOULD_answerUnimplemented() {
        // Arrange
        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        ServiceEndpointContext ctx = mock(ServiceEndpointContext.class);
        when(registry.contextByServiceFqn("pkg.Svc")).thenReturn(Optional.of(ctx));
        when(ctx.lookupMethod("pkg.Svc", "Secret"))
                .thenReturn(Optional.of(mock(com.google.protobuf.Descriptors.MethodDescriptor.class)));
        when(ctx.isMethodExposed("pkg.Svc", "Secret")).thenReturn(false);
        GrpcWebFilter filter = new GrpcWebFilter(
                registry,
                mock(GrpcMetadataPopulator.class),
                mock(GatewayAuthorizationEnforcer.class),
                new GatewayProperties());
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = grpcWebCall("/pkg.Svc/Secret");

        // Act
        filter.filter(exchange, chain).block();

        // Assert
        assertThat(exchange.getResponse().getHeaders().getFirst("grpc-status"))
                .isEqualTo(Integer.toString(io.grpc.Status.Code.UNIMPLEMENTED.value()));
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("GIVEN a policy that throttles WHEN a gRPC-Web call is authorized SHOULD answer RESOURCE_EXHAUSTED")
    void GIVEN_throttledCall_WHEN_calledOverGrpcWeb_SHOULD_answerResourceExhausted() {
        // Arrange
        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        ServiceEndpointContext ctx = mock(ServiceEndpointContext.class);
        var md = mock(com.google.protobuf.Descriptors.MethodDescriptor.class);
        var config = new dev.appiaworks.shiguchi.config.ServiceDefinition();
        config.setName("svc");
        when(registry.contextByServiceFqn("pkg.Svc")).thenReturn(Optional.of(ctx));
        when(ctx.lookupMethod("pkg.Svc", "Slow")).thenReturn(Optional.of(md));
        when(ctx.isMethodExposed("pkg.Svc", "Slow")).thenReturn(true);
        when(ctx.getConfig()).thenReturn(config);
        when(registry.findBySourceMethod("svc", "pkg.Svc", "Slow"))
                .thenReturn(Optional.of(mock(dev.appiaworks.shiguchi.routing.RouteDefinition.class)));
        GatewayAuthorizationEnforcer enforcer = mock(GatewayAuthorizationEnforcer.class);
        when(enforcer.enforce(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new dev.appiaworks.shiguchi.authz.ThrottledException(Duration.ofSeconds(3))));
        GrpcMetadataPopulator populator = mock(GrpcMetadataPopulator.class);
        when(populator.build(any(), any())).thenReturn(Mono.empty());
        GrpcWebFilter filter = new GrpcWebFilter(registry, populator, enforcer, new GatewayProperties());
        MockServerWebExchange exchange = grpcWebCall("/pkg.Svc/Slow");

        // Act
        filter.filter(exchange, mock(WebFilterChain.class)).block();

        // Assert
        assertThat(exchange.getResponse().getHeaders().getFirst("grpc-status"))
                .isEqualTo(Integer.toString(io.grpc.Status.Code.RESOURCE_EXHAUSTED.value()));
    }
}
