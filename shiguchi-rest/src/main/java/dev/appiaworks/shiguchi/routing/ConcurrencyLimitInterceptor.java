package dev.appiaworks.shiguchi.routing;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caps the calls in flight to one backend ({@code services[].max-concurrent-calls}).
 *
 * <p>A backend that has stopped keeping up does not fail; it gets slow, and every caller waits in
 * its queue. Past the limit, a call here fails at once with {@code UNAVAILABLE} (HTTP 503) instead,
 * which sheds the load at the gateway and tells the caller to back off while the calls already in
 * flight finish.
 *
 * <p>Installed outside the circuit breaker, so a rejection never counts as a backend failure — an
 * overloaded gateway must not also take the backend out of service — and outside gRPC's retries,
 * so one logical call holds one permit however many attempts it takes. One instance is shared by
 * every channel of a service's pool.
 */
public class ConcurrencyLimitInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLimitInterceptor.class);

    private final String serviceName;
    private final int limit;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();

    public ConcurrencyLimitInterceptor(String serviceName, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be at least 1");
        this.serviceName = serviceName;
        this.limit = limit;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        if (!acquire()) {
            if (rejected.incrementAndGet() % 1000 == 1) {
                log.warn(
                        "Service '{}': {} calls already in flight (max-concurrent-calls), rejecting",
                        serviceName,
                        limit);
            }
            return new CircuitBreakerInterceptor.FailFastCall<>(Status.UNAVAILABLE.withDescription("Service '"
                    + serviceName + "' has " + limit
                    + " calls in flight from this gateway, its max-concurrent-calls; retry shortly"));
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) active.decrementAndGet();
        };
        ClientCall<ReqT, RespT> call;
        try {
            call = next.newCall(method, callOptions);
        } catch (RuntimeException e) {
            release.run();
            throw e;
        }
        return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
            private volatile boolean started;

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                started = true;
                try {
                    super.start(
                            new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                                @Override
                                public void onClose(Status status, Metadata trailers) {
                                    release.run();
                                    super.onClose(status, trailers);
                                }
                            },
                            headers);
                } catch (RuntimeException e) {
                    release.run();
                    throw e;
                }
            }

            @Override
            public void cancel(String message, Throwable cause) {
                // A call cancelled before it started never gets onClose, and would keep its permit.
                if (!started) release.run();
                super.cancel(message, cause);
            }
        };
    }

    private boolean acquire() {
        while (true) {
            int current = active.get();
            if (current >= limit) return false;
            if (active.compareAndSet(current, current + 1)) return true;
        }
    }

    /** Calls holding a permit now. Visible for tests and diagnostics. */
    int active() {
        return active.get();
    }

    /** Calls turned away so far. */
    long rejected() {
        return rejected.get();
    }
}
