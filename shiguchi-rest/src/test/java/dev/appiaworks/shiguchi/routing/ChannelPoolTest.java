package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A pool of channels to one backend: calls rotate over the members, lifecycle calls reach every
 * member, and the pool reports the best state any member is in.
 */
@DisplayName("Channel pool")
class ChannelPoolTest {

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

    /** A member that records what the pool did to it. */
    private static final class FakeChannel extends ManagedChannel {
        int calls;
        boolean shutdown;
        boolean shutdownNow;
        boolean terminated;
        boolean idled;
        boolean backoffReset;
        ConnectivityState state = ConnectivityState.IDLE;
        Boolean connectionRequested;

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions options) {
            calls++;
            return new ClientCall<>() {
                @Override
                public void start(Listener<RespT> listener, Metadata headers) {}

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

        @Override
        public String authority() {
            return "orders:9090";
        }

        @Override
        public ManagedChannel shutdown() {
            shutdown = true;
            return this;
        }

        @Override
        public boolean isShutdown() {
            return shutdown || shutdownNow;
        }

        @Override
        public boolean isTerminated() {
            return terminated;
        }

        @Override
        public ManagedChannel shutdownNow() {
            shutdownNow = true;
            return this;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return terminated;
        }

        @Override
        public ConnectivityState getState(boolean requestConnection) {
            connectionRequested = requestConnection;
            return state;
        }

        @Override
        public void resetConnectBackoff() {
            backoffReset = true;
        }

        @Override
        public void enterIdle() {
            idled = true;
        }
    }

    @Test
    @DisplayName("GIVEN three members WHEN six calls are made SHOULD give each member two, in turn")
    void given_threeMembers_when_sixCalls_should_rotate() {
        var a = new FakeChannel();
        var b = new FakeChannel();
        var c = new FakeChannel();
        var pool = new ChannelPool(List.of(a, b, c));

        pool.newCall(METHOD, CallOptions.DEFAULT);
        assertThat(List.of(a.calls, b.calls, c.calls)).containsExactly(1, 0, 0);
        for (int i = 0; i < 5; i++) pool.newCall(METHOD, CallOptions.DEFAULT);

        assertThat(List.of(a.calls, b.calls, c.calls)).containsExactly(2, 2, 2);
    }

    @Test
    @DisplayName("GIVEN a pool WHEN shut down, gently or now SHOULD pass it to every member")
    void given_pool_when_shutDown_should_reachEveryMember() {
        var a = new FakeChannel();
        var b = new FakeChannel();
        var gentle = new ChannelPool(List.of(a, b));
        var c = new FakeChannel();
        var d = new FakeChannel();
        var now = new ChannelPool(List.of(c, d));

        gentle.shutdown();
        now.shutdownNow();

        assertThat(a.shutdown && b.shutdown).isTrue();
        assertThat(c.shutdownNow && d.shutdownNow).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN one member shut down and one not WHEN asked SHOULD not report the pool shut down until both are")
    void given_oneMemberShutDown_when_asked_should_waitForAll() {
        var a = new FakeChannel();
        var b = new FakeChannel();
        var pool = new ChannelPool(List.of(a, b));

        a.shutdown();
        assertThat(pool.isShutdown()).isFalse();
        b.shutdown();

        assertThat(pool.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("GIVEN one member terminated and one not WHEN awaited SHOULD report not terminated")
    void given_oneMemberTerminated_when_awaited_should_reportNotTerminated() throws Exception {
        var a = new FakeChannel();
        var b = new FakeChannel();
        var pool = new ChannelPool(List.of(a, b));
        a.terminated = true;

        assertThat(pool.isTerminated()).isFalse();
        assertThat(pool.awaitTermination(1, TimeUnit.MILLISECONDS)).isFalse();

        b.terminated = true;
        assertThat(pool.isTerminated()).isTrue();
        assertThat(pool.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN members in different states WHEN the state is read SHOULD report the best one and ask each to connect")
    void given_mixedStates_when_stateRead_should_reportBest() {
        var failing = new FakeChannel();
        failing.state = ConnectivityState.TRANSIENT_FAILURE;
        var connecting = new FakeChannel();
        connecting.state = ConnectivityState.CONNECTING;
        var pool = new ChannelPool(List.of(failing, connecting));

        assertThat(pool.getState(true)).isEqualTo(ConnectivityState.CONNECTING);
        assertThat(failing.connectionRequested).isTrue();
        assertThat(connecting.connectionRequested).isTrue();

        connecting.state = ConnectivityState.READY;
        assertThat(pool.getState(false)).isEqualTo(ConnectivityState.READY);
    }

    @Test
    @DisplayName("GIVEN a pool WHEN told to reset backoff or go idle SHOULD tell every member")
    void given_pool_when_resetOrIdle_should_reachEveryMember() {
        var a = new FakeChannel();
        var b = new FakeChannel();
        var pool = new ChannelPool(List.of(a, b));

        pool.resetConnectBackoff();
        pool.enterIdle();

        assertThat(a.backoffReset && b.backoffReset).isTrue();
        assertThat(a.idled && b.idled).isTrue();
        assertThat(pool.authority()).isEqualTo("orders:9090");
    }

    @Test
    @DisplayName("GIVEN no members WHEN a pool is built SHOULD refuse it")
    void given_noMembers_when_built_should_refuse() {
        assertThatThrownBy(() -> new ChannelPool(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
    }
}
