package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for GrpcProxyServerInterceptor.
 *
 * Strategy:
 * - ServerCall and ServerCallHandler are mocked with Mockito since they are abstract
 *   and have many methods. We stub only what's needed for the test scenarios.
 * - MethodDescriptor is built as a real (minimal) instance configured with the full
 *   method name being tested.
 * - Metadata is a real instance; headers can be empty or populated as the test scenario
 *   requires.
 * - We verify rate limiting behavior by checking whether call.close(Status.RESOURCE_EXHAUSTED, ...)
 *   was called and whether next.startCall(...) was called.
 */
@ExtendWith(MockitoExtension.class)
class GrpcProxyServerInterceptorTest {

    @Mock
    private ServerCall<String, String> call;

    @Mock
    private ServerCallHandler<String, String> next;

    private GatewayProperties properties;
    private GrpcProxyServerInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        interceptor = new GrpcProxyServerInterceptor(properties);
    }

    // =========================================================================
    // Test scenarios
    // =========================================================================

    @Test
    @DisplayName(
            "Given rate limiting disabled (default), when interceptCall is invoked, should delegate to next.startCall and return its result")
    void GIVEN_rateLimitingDisabled_WHEN_interceptCall_SHOULD_delegateToNext() {
        // Arrange
        assertThat(properties.getGrpc().getRateLimit().isEnabled()).isFalse();

        MethodDescriptor<String, String> methodDescriptor = buildMethodDescriptor("test.Service/Method");
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

        ServerCall.Listener<String> sentinelListener = new ServerCall.Listener<>() {};
        when(next.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(sentinelListener);

        Metadata headers = new Metadata();

        // Act
        ServerCall.Listener<String> result = interceptor.interceptCall(call, headers, next);

        // Assert — next.startCall was invoked exactly once
        verify(next).startCall(eq(call), any(Metadata.class));
        assertThat(result).isEqualTo(sentinelListener);
        // call.close should never be called when rate limiting is disabled
        verify(call, never()).close(any(Status.class), any(Metadata.class));
    }

    @Test
    @DisplayName(
            "Given rate limiting enabled with a high requestsPerSecond, when interceptCall is invoked once, should delegate to next.startCall")
    void GIVEN_rateLimitingEnabledWithHighLimit_WHEN_interceptCallOnce_SHOULD_delegateToNext() {
        // Arrange
        properties.getGrpc().getRateLimit().setEnabled(true);
        properties.getGrpc().getRateLimit().setRequestsPerSecond(1000.0);

        MethodDescriptor<String, String> methodDescriptor = buildMethodDescriptor("test.Service/Method");
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

        ServerCall.Listener<String> sentinelListener = new ServerCall.Listener<>() {};
        when(next.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(sentinelListener);

        Metadata headers = new Metadata();

        // Act
        ServerCall.Listener<String> result = interceptor.interceptCall(call, headers, next);

        // Assert
        verify(next).startCall(eq(call), any(Metadata.class));
        assertThat(result).isEqualTo(sentinelListener);
        verify(call, never()).close(any(Status.class), any(Metadata.class));
    }

    @Test
    @DisplayName(
            "Given rate limiting enabled with a very low requestsPerSecond, when interceptCall is invoked twice for the same method, should allow first call and reject second with RESOURCE_EXHAUSTED")
    void GIVEN_rateLimitingEnabledWithLowLimit_WHEN_interceptCallTwice_SHOULD_rejectSecondCall() {
        // Arrange
        properties.getGrpc().getRateLimit().setEnabled(true);
        properties.getGrpc().getRateLimit().setRequestsPerSecond(0.001); // One token every 1000 seconds

        String methodName = "test.Service/Method";
        MethodDescriptor<String, String> methodDescriptor = buildMethodDescriptor(methodName);
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

        ServerCall.Listener<String> sentinelListener = new ServerCall.Listener<>() {};
        when(next.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(sentinelListener);

        Metadata headers = new Metadata();

        // Act — first call should succeed
        ServerCall.Listener<String> firstResult = interceptor.interceptCall(call, headers, next);

        // Assert first call was delegated
        verify(next).startCall(eq(call), any(Metadata.class));
        assertThat(firstResult).isEqualTo(sentinelListener);
        verify(call, never()).close(any(Status.class), any(Metadata.class));

        // Act — second call should be rate limited
        ServerCall.Listener<String> secondResult = interceptor.interceptCall(call, headers, next);

        // Assert second call was rejected
        ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
        verify(call).close(statusCaptor.capture(), any(Metadata.class));
        assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(statusCaptor.getValue().getDescription()).contains("Rate limit exceeded");

        // next.startCall should have been called only once (for the first call)
        verify(next).startCall(eq(call), any(Metadata.class));

        // secondResult should be an empty listener (not the sentinel)
        assertThat(secondResult).isInstanceOf(ServerCall.Listener.class);
    }

    @Test
    @DisplayName(
            "Given rate limiting enabled, when interceptCall is invoked for two different method names, should allow both through (independent token buckets)")
    void GIVEN_rateLimitingEnabledWithLowLimit_WHEN_interceptCallDifferentMethods_SHOULD_allowBothThrough() {
        // Arrange
        properties.getGrpc().getRateLimit().setEnabled(true);
        properties.getGrpc().getRateLimit().setRequestsPerSecond(0.001);

        // Build two calls with different method names
        MethodDescriptor<String, String> method1 = buildMethodDescriptor("test.Service/Method1");
        MethodDescriptor<String, String> method2 = buildMethodDescriptor("test.Service/Method2");

        ServerCall.Listener<String> sentinelListener = new ServerCall.Listener<>() {};
        when(next.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(sentinelListener);

        Metadata headers = new Metadata();

        // Act — first call with Method1
        ServerCall<String, String> call1 = call;
        when(call1.getMethodDescriptor()).thenReturn(method1);
        ServerCall.Listener<String> result1 = interceptor.interceptCall(call1, headers, next);

        // Act — second call with Method2 (should be allowed since it's a different method)
        @SuppressWarnings("unchecked")
        ServerCall<String, String> call2 = org.mockito.Mockito.mock(ServerCall.class);
        when(call2.getMethodDescriptor()).thenReturn(method2);
        ServerCall.Listener<String> result2 = interceptor.interceptCall(call2, headers, next);

        // Assert — both calls should have been delegated to next.startCall
        verify(next, org.mockito.Mockito.times(2)).startCall(any(ServerCall.class), any(Metadata.class));
        assertThat(result1).isEqualTo(sentinelListener);
        assertThat(result2).isEqualTo(sentinelListener);
        // Neither should have been closed with RESOURCE_EXHAUSTED
        verify(call1, never()).close(any(Status.class), any(Metadata.class));
        verify(call2, never()).close(any(Status.class), any(Metadata.class));
    }

    @Test
    @DisplayName(
            "Given an inbound Metadata WITH an authorization header set, when interceptCall is invoked, should still delegate normally without throwing")
    void GIVEN_metadataWithAuthorizationHeader_WHEN_interceptCall_SHOULD_delegateNormally() {
        // Arrange
        properties.getGrpc().getRateLimit().setEnabled(false);

        MethodDescriptor<String, String> methodDescriptor = buildMethodDescriptor("test.Service/Method");
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

        ServerCall.Listener<String> sentinelListener = new ServerCall.Listener<>() {};
        when(next.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(sentinelListener);

        // Build metadata with authorization header
        Metadata headers = new Metadata();
        headers.put(GrpcProxyServerInterceptor.AUTHORIZATION_KEY, "Bearer token123");

        // Act
        ServerCall.Listener<String> result = interceptor.interceptCall(call, headers, next);

        // Assert
        verify(next).startCall(eq(call), any(Metadata.class));
        assertThat(result).isEqualTo(sentinelListener);
        verify(call, never()).close(any(Status.class), any(Metadata.class));
    }

    @Test
    @DisplayName(
            "Given an inbound Metadata WITHOUT an authorization header, when interceptCall is invoked, should still delegate normally without throwing")
    void GIVEN_metadataWithoutAuthorizationHeader_WHEN_interceptCall_SHOULD_delegateNormally() {
        // Arrange
        properties.getGrpc().getRateLimit().setEnabled(false);

        MethodDescriptor<String, String> methodDescriptor = buildMethodDescriptor("test.Service/Method");
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

        ServerCall.Listener<String> sentinelListener = new ServerCall.Listener<>() {};
        when(next.startCall(any(ServerCall.class), any(Metadata.class))).thenReturn(sentinelListener);

        Metadata headers = new Metadata(); // Empty headers

        // Act
        ServerCall.Listener<String> result = interceptor.interceptCall(call, headers, next);

        // Assert
        verify(next).startCall(eq(call), any(Metadata.class));
        assertThat(result).isEqualTo(sentinelListener);
        verify(call, never()).close(any(Status.class), any(Metadata.class));
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Builds a minimal MethodDescriptor<String, String> with the given full method name.
     * The descriptor is configured with simple string marshallers and UNARY call type.
     */
    private static MethodDescriptor<String, String> buildMethodDescriptor(String fullMethodName) {
        // Simple in-memory marshallers for String — we never actually serialize in these tests,
        // so the implementation is a no-op.
        MethodDescriptor.Marshaller<String> stringMarshaller = new MethodDescriptor.Marshaller<String>() {
            @Override
            public java.io.InputStream stream(String value) {
                return new java.io.ByteArrayInputStream(new byte[0]);
            }

            @Override
            public String parse(java.io.InputStream stream) {
                return "";
            }
        };

        return MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(fullMethodName)
                .setRequestMarshaller(stringMarshaller)
                .setResponseMarshaller(stringMarshaller)
                .build();
    }
}
