package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition.CircuitBreakerConfig;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A per-service circuit breaker on the channel to one backend.
 *
 * <p>Three states, the usual way round:
 *
 * <ul>
 *   <li><b>closed</b> — calls pass; a run of {@code failure-threshold} consecutive failures opens
 *       the breaker. Any success resets the run, so occasional failures never trip it.
 *   <li><b>open</b> — every call fails immediately with {@code UNAVAILABLE} and never reaches the
 *       backend, for {@code open-duration}.
 *   <li><b>half-open</b> — {@code half-open-calls} trial calls are let through. All succeeding
 *       closes the breaker; any failing opens it again for another full {@code open-duration}.
 * </ul>
 *
 * <p>The breaker is per gateway instance and per service. It deliberately counts only the status
 * codes in {@code failure-status-codes}: a backend answering {@code NOT_FOUND} or
 * {@code INVALID_ARGUMENT} is working correctly, and counting those would let a caller sending
 * bad requests take the backend out of service for everyone.
 *
 * <p>Installed above gRPC's retry machinery, so one logical call is one outcome here however many
 * attempts it took.
 */
public class CircuitBreakerInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerInterceptor.class);

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String serviceName;
    private final CircuitBreakerConfig config;
    private final Set<Status.Code> failureCodes;
    private final long openDurationNanos;
    private final LongSupplier nanoClock;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAtNanos = new AtomicLong();
    /** Trial calls still to be admitted while half-open; also gates how many may run at once. */
    private final AtomicInteger halfOpenPermits = new AtomicInteger();
    /** Trial calls that have come back successfully in the current half-open round. */
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger();

    public CircuitBreakerInterceptor(String serviceName, CircuitBreakerConfig config) {
        this(serviceName, config, System::nanoTime);
    }

    /** Test seam: an injectable clock, so open-duration expiry needs no sleeping. */
    CircuitBreakerInterceptor(String serviceName, CircuitBreakerConfig config, LongSupplier nanoClock) {
        this.serviceName = serviceName;
        this.config = config;
        this.nanoClock = nanoClock;
        this.openDurationNanos = config.getOpenDuration().toNanos();
        Set<Status.Code> codes = EnumSet.noneOf(Status.Code.class);
        for (String name : config.getFailureStatusCodes()) {
            codes.add(RetryServiceConfig.validate(name));
        }
        this.failureCodes = codes;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        if (!admit()) {
            return new FailFastCall<>(Status.UNAVAILABLE.withDescription("Circuit breaker open for service '"
                    + serviceName + "': the backend has been failing, so this call was not attempted. Retry after "
                    + config.getOpenDuration().toSeconds() + "s."));
        }
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(
                        new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                record(status);
                                super.onClose(status, trailers);
                            }
                        },
                        headers);
            }
        };
    }

    /** Whether this call may proceed, moving the breaker out of OPEN when its time is up. */
    private boolean admit() {
        State current = state.get();
        if (current == State.CLOSED) return true;

        if (current == State.OPEN) {
            if (nanoClock.getAsLong() - openedAtNanos.get() < openDurationNanos) return false;
            // Time is up: let one round of trial calls through.
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                halfOpenPermits.set(Math.max(1, config.getHalfOpenCalls()));
                halfOpenSuccesses.set(0);
                log.info(
                        "Circuit breaker for service '{}' is half-open: trying {} call(s)",
                        serviceName,
                        config.getHalfOpenCalls());
            }
        }
        // Half-open: admit only as many trial calls as configured, no more.
        return takePermit();
    }

    /** Takes one half-open permit without ever letting the count go negative. */
    private boolean takePermit() {
        while (true) {
            int available = halfOpenPermits.get();
            if (available <= 0) return false;
            if (halfOpenPermits.compareAndSet(available, available - 1)) return true;
        }
    }

    private void record(Status status) {
        boolean failure = failureCodes.contains(status.getCode());
        if (state.get() == State.HALF_OPEN) {
            recordTrial(failure, status);
        } else if (failure) {
            recordFailure(status);
        } else {
            consecutiveFailures.set(0);
        }
    }

    /** A trial call came back: all of them succeeding closes the breaker, one failure reopens it. */
    private void recordTrial(boolean failure, Status status) {
        if (failure) {
            open(status);
            return;
        }
        int succeeded = halfOpenSuccesses.incrementAndGet();
        if (succeeded >= Math.max(1, config.getHalfOpenCalls()) && state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            consecutiveFailures.set(0);
            log.info("Circuit breaker for service '{}' closed: the backend is answering again", serviceName);
        }
    }

    private void recordFailure(Status status) {
        if (consecutiveFailures.incrementAndGet() >= config.getFailureThreshold()) {
            open(status);
        }
    }

    private void open(Status status) {
        State previous = state.getAndSet(State.OPEN);
        openedAtNanos.set(nanoClock.getAsLong());
        consecutiveFailures.set(0);
        halfOpenPermits.set(0);
        halfOpenSuccesses.set(0);
        if (previous != State.OPEN) {
            log.warn(
                    "Circuit breaker for service '{}' opened after {} (last status {}): failing calls fast for {}",
                    serviceName,
                    previous == State.HALF_OPEN ? "a failed trial call" : config.getFailureThreshold() + " failures",
                    status.getCode(),
                    config.getOpenDuration());
        }
    }

    /** Visible for tests. */
    State state() {
        return state.get();
    }

    /** A call that never reaches the network: it closes with the breaker's status at start(). */
    static final class FailFastCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

        private final Status status;

        FailFastCall(Status status) {
            this.status = status;
        }

        @Override
        public void start(Listener<RespT> listener, Metadata headers) {
            listener.onClose(status, new Metadata());
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {}

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {}
    }
}
