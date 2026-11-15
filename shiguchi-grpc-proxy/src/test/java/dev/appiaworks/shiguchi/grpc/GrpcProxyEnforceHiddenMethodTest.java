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
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcProxyEnforceHiddenMethodTest {

    @Test
    @DisplayName(
            "Given ENFORCE mode and a method the proxy does not expose, when called without a token, should answer UNIMPLEMENTED and never consult the guard")
    void GIVEN_enforceAndUnexposedMethod_WHEN_calledWithoutToken_SHOULD_unimplementedBeforeAuth() throws Exception {
        GrpcRouteRegistry routes = mock(GrpcRouteRegistry.class);
        when(routes.contextByServiceFqn(any())).thenReturn(Optional.empty());
        ProxyCallGuard guard = mock(ProxyCallGuard.class);
        GatewayProperties properties = new GatewayProperties();
        properties.getGrpc().getSecurity().setMode(GrpcSecurityConfig.Mode.ENFORCE);
        GrpcProxyServerInterceptor interceptor = new GrpcProxyServerInterceptor(properties, guard);
        String name = "enforce-hidden-" + System.nanoTime();
        Server server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .fallbackHandlerRegistry(new GrpcProxyHandlerRegistry(routes, new GrpcDescriptorCache()))
                .intercept(interceptor)
                .build()
                .start();
        ManagedChannel channel =
                InProcessChannelBuilder.forName(name).directExecutor().build();
        try {
            MethodDescriptor<byte[], byte[]> md = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName("dev.example.Hidden/Secret")
                    .setRequestMarshaller(new Bytes())
                    .setResponseMarshaller(new Bytes())
                    .build();

            assertThatThrownBy(
                            () -> ClientCalls.blockingUnaryCall(channel, md, io.grpc.CallOptions.DEFAULT, new byte[0]))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(io.grpc.Status.Code.UNIMPLEMENTED));
            verify(guard, never()).admit(any(), any());
        } finally {
            channel.shutdownNow();
            server.shutdownNow();
            interceptor.destroy();
        }
    }

    private static final class Bytes implements MethodDescriptor.Marshaller<byte[]> {
        @Override
        public java.io.InputStream stream(byte[] value) {
            return new java.io.ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(java.io.InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
