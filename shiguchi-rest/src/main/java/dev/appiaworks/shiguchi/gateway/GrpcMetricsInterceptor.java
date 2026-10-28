package dev.appiaworks.shiguchi.gateway;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * gRPC client-side Micrometer interceptor.
 *
 * Registers on every outbound ManagedChannel (see DynamicRouteLoader) and records:
 *
 *   grpc.client.call.started            — counter  tags: grpc_service, grpc_method
 *   grpc.client.call.duration           — timer    tags: grpc_service, grpc_method, grpc_status
 *   grpc.client.call.messages.sent      — counter  tags: grpc_service, grpc_method
 *   grpc.client.call.messages.received  — counter  tags: grpc_service, grpc_method
 *
 * The "grpc_status" tag value is the gRPC Status.Code name (OK, NOT_FOUND, etc.)
 * so dashboards can slice by error type without parsing exception messages.
 *
 * The message counters are what tell a streaming call apart from a unary one: a unary call
 * sends and receives one message, a server-streaming call can receive thousands. They count
 * the messages of the logical call — gRPC retries sit below this interceptor, so a retried
 * request is still one message sent.
 *
 * Tag values come from the methods the gateway loaded from descriptors, never from caller
 * input, so the number of series is bounded by the protos. The meters without a status tag are
 * resolved once per method and cached, keeping a registry lookup off every streamed message.
 *
 * Suggested Grafana panels:
 *   - Rate of started calls by service/method  (counter rate)
 *   - p50/p95/p99 latency by service/method    (timer percentile)
 *   - Error rate by status code                 (counter filtered status != OK)
 *   - Messages per call by method               (received rate / started rate)
 */
@Component
public class GrpcMetricsInterceptor implements ClientInterceptor {

    private static final String METRIC_STARTED = "grpc.client.call.started";
    private static final String METRIC_DURATION = "grpc.client.call.duration";
    private static final String METRIC_MESSAGES_SENT = "grpc.client.call.messages.sent";
    private static final String METRIC_MESSAGES_RECEIVED = "grpc.client.call.messages.received";

    private final MeterRegistry meterRegistry;

    /** The status-independent meters of each method, keyed by full method name. */
    private final Map<String, MethodMeters> methodMeters = new ConcurrentHashMap<>();

    /** The status-independent meters of one method, plus its duration timer per status, created on first use. */
    private record MethodMeters(
            String service, String method, Counter started, Counter sent, Counter received, Timer[] durationByStatus) {}

    /**
     * @param meterRegistry Boot's registry when Actuator is present; without one (a UI starter used
     *     on its own) the meters go to Micrometer's global registry rather than failing startup
     */
    public GrpcMetricsInterceptor(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry.getIfAvailable(() -> Metrics.globalRegistry);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        MethodMeters meters = methodMeters.computeIfAbsent(method.getFullMethodName(), this::metersFor);
        meters.started().increment();

        long startNanos = System.nanoTime();

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {

            @Override
            public void sendMessage(ReqT message) {
                meters.sent().increment();
                super.sendMessage(message);
            }

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(
                        new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                            @Override
                            public void onMessage(RespT message) {
                                meters.received().increment();
                                super.onMessage(message);
                            }

                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                long durationNanos = System.nanoTime() - startNanos;
                                durationTimer(meters, status.getCode()).record(durationNanos, TimeUnit.NANOSECONDS);
                                super.onClose(status, trailers);
                            }
                        },
                        headers);
            }
        };
    }

    /**
     * The method's duration timer for a status, registered the first time that status is seen.
     * Building the timer per call would sort its tags and look it up in the registry each time,
     * which the hot path is better without; the array is indexed by the status code's ordinal.
     */
    private Timer durationTimer(MethodMeters meters, Status.Code code) {
        Timer timer = meters.durationByStatus()[code.ordinal()];
        if (timer == null) {
            timer = Timer.builder(METRIC_DURATION)
                    .tag("grpc_service", meters.service())
                    .tag("grpc_method", meters.method())
                    .tag("grpc_status", code.name())
                    .register(meterRegistry);
            meters.durationByStatus()[code.ordinal()] = timer; // idempotent: the registry returns the same meter
        }
        return timer;
    }

    private MethodMeters metersFor(String fullMethodName) {
        String[] parts = fullMethodName.split("/", 2);
        String grpcService = parts.length > 0 ? parts[0] : "unknown";
        String grpcMethod = parts.length > 1 ? parts[1] : "unknown";
        return new MethodMeters(
                grpcService,
                grpcMethod,
                counter(METRIC_STARTED, grpcService, grpcMethod),
                counter(METRIC_MESSAGES_SENT, grpcService, grpcMethod),
                counter(METRIC_MESSAGES_RECEIVED, grpcService, grpcMethod),
                new Timer[Status.Code.values().length]);
    }

    private Counter counter(String name, String grpcService, String grpcMethod) {
        return Counter.builder(name)
                .tag("grpc_service", grpcService)
                .tag("grpc_method", grpcMethod)
                .register(meterRegistry);
    }
}
