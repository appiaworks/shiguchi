package dev.appiaworks.shiguchi.routing;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Several channels to one backend behind one {@link ManagedChannel} ({@code services[].channels}).
 *
 * <p>Each member opens its own HTTP/2 connection per address, and calls go to the members in
 * turn. That lifts the ceilings of a single connection — the backend's
 * {@code MAX_CONCURRENT_STREAMS}, one event-loop thread's worth of framing — and, behind an L4
 * balancer such as a Kubernetes ClusterIP, gives the balancer several connections to spread over
 * pods instead of one it pinned at connect time.
 *
 * <p>Round-robin rather than least-loaded on purpose: calls are short and uniform enough that the
 * difference does not pay for the bookkeeping on every call.
 */
final class ChannelPool extends ManagedChannel {

    private final ManagedChannel[] members;
    private final AtomicInteger next = new AtomicInteger();

    ChannelPool(List<ManagedChannel> members) {
        if (members.isEmpty()) throw new IllegalArgumentException("a pool needs at least one channel");
        this.members = members.toArray(new ManagedChannel[0]);
    }

    int size() {
        return members.length;
    }

    private ManagedChannel pick() {
        return members[Math.floorMod(next.getAndIncrement(), members.length)];
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
            MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
        return pick().newCall(methodDescriptor, callOptions);
    }

    @Override
    public String authority() {
        return members[0].authority();
    }

    @Override
    public ManagedChannel shutdown() {
        for (ManagedChannel member : members) member.shutdown();
        return this;
    }

    @Override
    public boolean isShutdown() {
        for (ManagedChannel member : members) {
            if (!member.isShutdown()) return false;
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (ManagedChannel member : members) {
            if (!member.isTerminated()) return false;
        }
        return true;
    }

    @Override
    public ManagedChannel shutdownNow() {
        for (ManagedChannel member : members) member.shutdownNow();
        return this;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        for (ManagedChannel member : members) {
            long left = deadline - System.nanoTime();
            if (left <= 0 || !member.awaitTermination(left, TimeUnit.NANOSECONDS)) return false;
        }
        return true;
    }

    /**
     * The best state among the members: the pool can serve a call as soon as any member is
     * {@code READY}. {@code requestConnection} is passed to every member.
     */
    @Override
    public ConnectivityState getState(boolean requestConnection) {
        ConnectivityState best = ConnectivityState.SHUTDOWN;
        for (ManagedChannel member : members) {
            ConnectivityState state = member.getState(requestConnection);
            if (rank(state) < rank(best)) best = state;
        }
        return best;
    }

    private static int rank(ConnectivityState state) {
        return switch (state) {
            case READY -> 0;
            case CONNECTING -> 1;
            case IDLE -> 2;
            case TRANSIENT_FAILURE -> 3;
            case SHUTDOWN -> 4;
        };
    }

    @Override
    public void resetConnectBackoff() {
        for (ManagedChannel member : members) member.resetConnectBackoff();
    }

    @Override
    public void enterIdle() {
        for (ManagedChannel member : members) member.enterIdle();
    }
}
