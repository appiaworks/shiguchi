package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per-service outbound compression.
 *
 * <p>The interceptor is built by a private static method on {@link GrpcChannelFactory}; the tests
 * reach it reflectively rather than standing up a channel, because what needs checking is which
 * compressor ends up on the call options, and a real channel would only obscure that behind a
 * connection attempt.
 */
@DisplayName("Per-service compression")
class GrpcChannelCompressionTest {

    private static ServiceDefinition service(String compression) {
        ServiceDefinition svc = new ServiceDefinition();
        svc.setName("catalog");
        svc.setHost("localhost");
        svc.setPort(9090);
        if (compression != null) svc.setCompression(compression);
        return svc;
    }

    /** {@code GrpcChannelFactory.compressorName(svc)}. */
    private static String compressorName(ServiceDefinition svc) throws Exception {
        Method m = GrpcChannelFactory.class.getDeclaredMethod("compressorName", ServiceDefinition.class);
        m.setAccessible(true);
        return (String) m.invoke(null, svc);
    }

    /** {@code GrpcChannelFactory.compressionInterceptor(name)}. */
    private static ClientInterceptor interceptor(String compressor) throws Exception {
        Method m = GrpcChannelFactory.class.getDeclaredMethod("compressionInterceptor", String.class);
        m.setAccessible(true);
        return (ClientInterceptor) m.invoke(null, compressor);
    }

    @Test
    @DisplayName(
            "GIVEN no compression configured WHEN the channel is built SHOULD add no interceptor, leaving calls uncompressed")
    void given_default_when_building_should_addNothing() throws Exception {
        assertThat(compressorName(service(null))).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN 'identity' WHEN resolved SHOULD be treated as no compression, since that is gRPC's own name for it")
    void given_identity_when_resolved_should_meanNoCompression() throws Exception {
        assertThat(compressorName(service("identity"))).isNull();
        assertThat(compressorName(service("IDENTITY"))).isNull();
        assertThat(compressorName(service("  "))).isNull();
    }

    @Test
    @DisplayName("GIVEN gzip WHEN resolved SHOULD pass the codec name through to gRPC")
    void given_gzip_when_resolved_should_passItThrough() throws Exception {
        assertThat(compressorName(service("gzip"))).isEqualTo("gzip");
        assertThat(compressorName(service("  gzip  "))).isEqualTo("gzip");
    }

    @Test
    @DisplayName(
            "GIVEN a custom codec name WHEN resolved SHOULD pass it through too, so a registered codec works without a code change here")
    void given_customCodec_when_resolved_should_passItThrough() throws Exception {
        assertThat(compressorName(service("deflate"))).isEqualTo("deflate");
    }

    @Test
    @DisplayName("GIVEN the interceptor WHEN a call is made SHOULD set the compressor on that call's options")
    void given_interceptor_when_callMade_should_setTheCompressor() throws Exception {
        AtomicReference<CallOptions> seen = new AtomicReference<>();
        Channel channel = new Channel() {
            @Override
            public <Q, S> ClientCall<Q, S> newCall(MethodDescriptor<Q, S> method, CallOptions callOptions) {
                seen.set(callOptions);
                return null;
            }

            @Override
            public String authority() {
                return "test";
            }
        };

        interceptor("gzip").interceptCall(null, CallOptions.DEFAULT, channel);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().getCompressor()).isEqualTo("gzip");
    }

    @Test
    @DisplayName("GIVEN existing call options WHEN intercepted SHOULD keep them, changing only the compressor")
    void given_existingOptions_when_intercepted_should_keepThem() throws Exception {
        AtomicReference<CallOptions> seen = new AtomicReference<>();
        Channel channel = new Channel() {
            @Override
            public <Q, S> ClientCall<Q, S> newCall(MethodDescriptor<Q, S> method, CallOptions callOptions) {
                seen.set(callOptions);
                return null;
            }

            @Override
            public String authority() {
                return "test";
            }
        };
        // A deadline set further up the stack must survive: dropping it would turn a bounded call
        // into an unbounded one.
        CallOptions withDeadline = CallOptions.DEFAULT.withDeadlineAfter(5, java.util.concurrent.TimeUnit.SECONDS);

        interceptor("gzip").interceptCall(null, withDeadline, channel);

        assertThat(seen.get().getDeadline()).isNotNull();
        assertThat(seen.get().getCompressor()).isEqualTo("gzip");
    }
}
