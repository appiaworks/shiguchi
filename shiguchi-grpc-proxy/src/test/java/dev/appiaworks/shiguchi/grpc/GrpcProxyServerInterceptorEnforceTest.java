package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GrpcSecurityConfig;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The ENFORCE mode of {@link GrpcProxyServerInterceptor}, with a fake {@link ProxyCallGuard}. */
class GrpcProxyServerInterceptorEnforceTest {

    private static final Metadata.Key<String> RETRY_AFTER =
            Metadata.Key.of("retry-after", Metadata.ASCII_STRING_MARSHALLER);

    private final GatewayProperties properties = new GatewayProperties();
    private GrpcProxyServerInterceptor interceptor;

    @AfterEach
    void tearDown() {
        if (interceptor != null) interceptor.destroy();
    }

    @SuppressWarnings("unchecked")
    private ServerCall<String, String> call(String fullMethod) {
        ServerCall<String, String> call = mock(ServerCall.class);
        MethodDescriptor<String, String> md = MethodDescriptor.<String, String>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(fullMethod)
                .setRequestMarshaller(new Marshaller())
                .setResponseMarshaller(new Marshaller())
                .build();
        when(call.getMethodDescriptor()).thenReturn(md);
        return call;
    }

    private GrpcProxyServerInterceptor enforcing(ProxyCallGuard guard) {
        properties.getGrpc().getSecurity().setMode(GrpcSecurityConfig.Mode.ENFORCE);
        interceptor = new GrpcProxyServerInterceptor(properties, guard);
        return interceptor;
    }

    @Test
    @DisplayName("Given PASSTHROUGH mode with a guard bean present, when a call arrives, should not consult the guard")
    @SuppressWarnings("unchecked")
    void GIVEN_passthrough_WHEN_call_SHOULD_notConsultGuard() {
        ProxyCallGuard guard = mock(ProxyCallGuard.class);
        interceptor = new GrpcProxyServerInterceptor(properties, guard);
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call("a.B/C"), new Metadata(), next);

        verify(guard, never()).admit(any(), any());
        verify(next).startCall(any(), any());
    }

    @Test
    @DisplayName(
            "Given ENFORCE mode and no guard bean, when the interceptor is built, should fail with a clear message")
    void GIVEN_enforceWithoutGuard_WHEN_built_SHOULD_failFast() {
        properties.getGrpc().getSecurity().setMode(GrpcSecurityConfig.Mode.ENFORCE);

        assertThatThrownBy(() -> new GrpcProxyServerInterceptor(properties, (ProxyCallGuard) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shiguchi.grpc.security.mode")
                .hasMessageContaining("ProxyCallGuard");
    }

    @Test
    @DisplayName(
            "Given a guard that denies with UNAUTHENTICATED, when a call arrives, should close it and not start it")
    @SuppressWarnings("unchecked")
    void GIVEN_guardDenies_WHEN_call_SHOULD_closeWithStatusAndNotStart() {
        enforcing((method, headers) -> ProxyCallGuard.Decision.deny(Status.UNAUTHENTICATED));
        ServerCall<String, String> call = call("a.B/C");
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, new Metadata(), next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    @DisplayName(
            "Given a throttled decision with a retry-after trailer, when a call arrives, should close with the trailer")
    @SuppressWarnings("unchecked")
    void GIVEN_throttled_WHEN_call_SHOULD_closeWithRetryAfterTrailer() {
        Metadata trailers = new Metadata();
        trailers.put(RETRY_AFTER, "7");
        enforcing((method, headers) -> ProxyCallGuard.Decision.deny(Status.RESOURCE_EXHAUSTED, trailers));
        ServerCall<String, String> call = call("a.B/C");

        interceptor.interceptCall(call, new Metadata(), mock(ServerCallHandler.class));

        ArgumentCaptor<Metadata> sent = ArgumentCaptor.forClass(Metadata.class);
        verify(call).close(any(Status.class), sent.capture());
        assertThat(sent.getValue().get(RETRY_AFTER)).isEqualTo("7");
    }

    @Test
    @DisplayName("Given a guard that throws, when a call arrives, should deny with PERMISSION_DENIED")
    @SuppressWarnings("unchecked")
    void GIVEN_guardThrows_WHEN_call_SHOULD_denyPermissionDenied() {
        enforcing((method, headers) -> {
            throw new IllegalStateException("boom");
        });
        ServerCall<String, String> call = call("a.B/C");

        interceptor.interceptCall(call, new Metadata(), mock(ServerCallHandler.class));

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("Given an admitted call, when the handler starts, should see the principal on the call context")
    @SuppressWarnings("unchecked")
    void GIVEN_admitted_WHEN_handlerStarts_SHOULD_seePrincipalOnContext() {
        enforcing((method, headers) -> ProxyCallGuard.Decision.allow("alice", null));
        AtomicReference<String> seen = new AtomicReference<>();
        ServerCallHandler<String, String> next = (c, h) -> {
            seen.set(GrpcProxyServerInterceptor.PRINCIPAL.get());
            return new ServerCall.Listener<>() {};
        };

        ServerCall.Listener<String> listener = interceptor.interceptCall(call("a.B/C"), new Metadata(), next);
        assertThat(listener).isNotNull();

        assertThat(seen.get()).isEqualTo("alice");
        assertThat(GrpcProxyServerInterceptor.PRINCIPAL.get()).isNull();
    }

    @Test
    @DisplayName(
            "Given a token that expires shortly, when the call is open past expiry, should cancel its context as expired")
    void GIVEN_shortExp_WHEN_openPastExpiry_SHOULD_cancelContextAsExpired() throws Exception {
        enforcing((method, headers) ->
                ProxyCallGuard.Decision.allow("alice", Instant.now().plusMillis(150)));
        AtomicReference<Context> callContext = new AtomicReference<>();
        ServerCallHandler<String, String> next = (c, h) -> {
            callContext.set(Context.current());
            return new ServerCall.Listener<>() {};
        };

        interceptor.interceptCall(call("a.B/C"), new Metadata(), next);

        long deadline = System.currentTimeMillis() + 5000;
        while (!callContext.get().isCancelled() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(callContext.get().isCancelled()).isTrue();
        assertThat(callContext.get().cancellationCause()).isSameAs(GrpcProxyServerInterceptor.TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("Given a token already expired, when a call arrives, should close with UNAUTHENTICATED")
    @SuppressWarnings("unchecked")
    void GIVEN_expiredToken_WHEN_call_SHOULD_closeUnauthenticated() {
        enforcing((method, headers) ->
                ProxyCallGuard.Decision.allow("alice", Instant.now().minusSeconds(1)));
        ServerCall<String, String> call = call("a.B/C");
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, new Metadata(), next);

        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertThat(status.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        verify(next, never()).startCall(any(), any());
    }

    @Test
    @DisplayName("Given a service the gateway hosts itself, when a call arrives in ENFORCE mode, should skip the guard")
    @SuppressWarnings("unchecked")
    void GIVEN_hostedService_WHEN_call_SHOULD_skipGuard() {
        ProxyCallGuard guard = mock(ProxyCallGuard.class);
        enforcing(guard);
        interceptor.hostedServices(Set.of("dev.appiaworks.shiguchi.push.PushService"));
        ServerCallHandler<String, String> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call("dev.appiaworks.shiguchi.push.PushService/Subscribe"), new Metadata(), next);

        verify(guard, never()).admit(any(), any());
        verify(next).startCall(any(), any());
    }

    private static final class Marshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public java.io.InputStream stream(String value) {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override
        public String parse(java.io.InputStream stream) {
            return "";
        }
    }
}
