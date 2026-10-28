package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The per-backend concurrency limit: a call over it fails fast without reaching the channel, and
 * every way a call can end — closing, failing to start, being cancelled before start — returns
 * its permit.
 */
@DisplayName("Concurrency limit interceptor")
class ConcurrencyLimitInterceptorTest {

    private static final MethodDescriptor.Marshaller<String> STRINGS = new MethodDescriptor.Marshaller<>() {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes());
        }

        @Override
        public String parse(InputStream stream) {
            return "";
        }
    };

    private static final MethodDescriptor<String, String> METHOD = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.Service/Method")
            .setRequestMarshaller(STRINGS)
            .setResponseMarshaller(STRINGS)
            .build();

    /** A channel whose calls stay open until {@link #finish} closes them; counts calls that reached it. */
    private static final class HoldingChannel extends Channel {
        final List<ClientCall.Listener<?>> open = new ArrayList<>();
        int reached;

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions options) {
            reached++;
            return new ClientCall<>() {
                @Override
                public void start(Listener<RespT> listener, Metadata headers) {
                    open.add(listener);
                }

                @Override
                public void request(int numMessages) {}

                @Override
                public void cancel(String message, Throwable cause) {}

                @Override
                public void halfClose() {}

                @Override
                public void sendMessage(ReqT message) {}
            };
        }

        void finish(int index) {
            open.get(index).onClose(Status.OK, new Metadata());
        }

        @Override
        public String authority() {
            return "test";
        }
    }

    /** Starts a call and returns where its close status will land. */
    private static AtomicReference<Status> start(Channel channel) {
        AtomicReference<Status> closed = new AtomicReference<>();
        ClientCall<String, String> call = channel.newCall(METHOD, CallOptions.DEFAULT);
        call.start(
                new ClientCall.Listener<>() {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        closed.set(status);
                    }
                },
                new Metadata());
        return closed;
    }

    @Test
    @DisplayName(
            "GIVEN limit 1 and a call in flight WHEN a second call starts SHOULD fail it with UNAVAILABLE without reaching the channel")
    void given_callInFlight_when_secondStarts_should_rejectWithoutReachingChannel() {
        var backend = new HoldingChannel();
        var limiter = new ConcurrencyLimitInterceptor("orders", 1);
        Channel channel = ClientInterceptors.intercept(backend, limiter);

        AtomicReference<Status> first = start(channel);
        AtomicReference<Status> second = start(channel);

        assertThat(first.get()).as("still in flight").isNull();
        assertThat(second.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(second.get().getDescription()).contains("orders").contains("max-concurrent-calls");
        assertThat(backend.reached).isEqualTo(1);
        assertThat(limiter.rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN limit 1 WHEN the call in flight closes SHOULD let the next call through")
    void given_callInFlight_when_itCloses_should_admitNextCall() {
        var backend = new HoldingChannel();
        var limiter = new ConcurrencyLimitInterceptor("orders", 1);
        Channel channel = ClientInterceptors.intercept(backend, limiter);
        AtomicReference<Status> first = start(channel);

        backend.finish(0);
        AtomicReference<Status> next = start(channel);

        assertThat(first.get().getCode()).isEqualTo(Status.Code.OK);
        assertThat(next.get()).as("admitted and in flight").isNull();
        assertThat(backend.reached).isEqualTo(2);
        assertThat(limiter.active()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN limit 2 WHEN two calls are in flight SHOULD admit both and hold two permits")
    void given_limitTwo_when_twoCalls_should_admitBoth() {
        var backend = new HoldingChannel();
        var limiter = new ConcurrencyLimitInterceptor("orders", 2);
        Channel channel = ClientInterceptors.intercept(backend, limiter);

        start(channel);
        start(channel);

        assertThat(backend.reached).isEqualTo(2);
        assertThat(limiter.active()).isEqualTo(2);
        assertThat(limiter.rejected()).isZero();
    }

    @Test
    @DisplayName("GIVEN a call cancelled before it started WHEN the next call starts SHOULD have returned the permit")
    void given_cancelledBeforeStart_when_nextCall_should_haveReleasedPermit() {
        var backend = new HoldingChannel();
        var limiter = new ConcurrencyLimitInterceptor("orders", 1);
        Channel channel = ClientInterceptors.intercept(backend, limiter);

        ClientCall<String, String> abandoned = channel.newCall(METHOD, CallOptions.DEFAULT);
        abandoned.cancel("caller went away", null);
        start(channel);

        assertThat(limiter.rejected()).isZero();
        assertThat(limiter.active()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a call closed twice by a misbehaving channel WHEN counted SHOULD release its permit only once")
    void given_doubleClose_when_counted_should_releaseOnce() {
        var backend = new HoldingChannel();
        var limiter = new ConcurrencyLimitInterceptor("orders", 2);
        Channel channel = ClientInterceptors.intercept(backend, limiter);
        start(channel);
        start(channel);

        backend.finish(0);
        backend.finish(0);

        assertThat(limiter.active()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN a limit below 1 WHEN constructed SHOULD refuse it")
    void given_limitBelowOne_when_constructed_should_refuse() {
        assertThatThrownBy(() -> new ConcurrencyLimitInterceptor("orders", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }
}
