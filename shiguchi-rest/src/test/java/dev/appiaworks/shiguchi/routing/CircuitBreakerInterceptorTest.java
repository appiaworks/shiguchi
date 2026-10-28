package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.ServiceDefinition.CircuitBreakerConfig;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The breaker's state machine, driven through the real interceptor API against a fake channel: a
 * run of failures opens it, an open breaker never reaches the backend, and the half-open trial
 * decides whether it closes or opens again.
 */
@DisplayName("CircuitBreakerInterceptor")
class CircuitBreakerInterceptorTest {

    private static final MethodDescriptor<String, String> METHOD = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("orders.OrderService/GetOrder")
            .setRequestMarshaller(new StringMarshaller())
            .setResponseMarshaller(new StringMarshaller())
            .build();

    private final AtomicLong nanos = new AtomicLong();
    private final AtomicInteger backendCalls = new AtomicInteger();
    private final Deque<Status> outcomes = new ArrayDeque<>();

    private static CircuitBreakerConfig config(int threshold, int halfOpenCalls) {
        var config = new CircuitBreakerConfig();
        config.setEnabled(true);
        config.setFailureThreshold(threshold);
        config.setHalfOpenCalls(halfOpenCalls);
        config.setOpenDuration(Duration.ofSeconds(30));
        return config;
    }

    /** A channel that answers each call with the next queued status, counting what reaches it. */
    private final Channel backend = new Channel() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
            return new ClientCall<>() {
                @Override
                public void start(Listener<RespT> listener, Metadata headers) {
                    backendCalls.incrementAndGet();
                    Status status = outcomes.isEmpty() ? Status.OK : outcomes.poll();
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
            };
        }

        @Override
        public String authority() {
            return "test";
        }
    };

    /** Runs one call through the interceptor and returns the status the caller sees. */
    private Status call(CircuitBreakerInterceptor breaker) {
        Channel channel = ClientInterceptors.intercept(backend, breaker);
        var result = new Status[1];
        ClientCall<String, String> call = channel.newCall(METHOD, CallOptions.DEFAULT);
        call.start(
                new ClientCall.Listener<>() {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        result[0] = status;
                    }
                },
                new Metadata());
        return result[0];
    }

    private CircuitBreakerInterceptor breaker(CircuitBreakerConfig config) {
        return new CircuitBreakerInterceptor("orders", config, nanos::get);
    }

    @Test
    @DisplayName(
            "GIVEN failures below the threshold WHEN a call succeeds SHOULD reset the run so the breaker stays closed")
    void given_intermittentFailures_when_successBetween_should_stayClosed() {
        CircuitBreakerInterceptor breaker = breaker(config(3, 1));
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.OK, Status.UNAVAILABLE));

        for (int i = 0; i < 4; i++) call(breaker);

        assertThat(breaker.state()).isEqualTo(CircuitBreakerInterceptor.State.CLOSED);
        assertThat(backendCalls.get()).isEqualTo(4);
    }

    @Test
    @DisplayName(
            "GIVEN consecutive failures reaching the threshold WHEN another call arrives SHOULD open and fail it without touching the backend")
    void given_thresholdReached_when_callingAgain_should_failFast() {
        CircuitBreakerInterceptor breaker = breaker(config(3, 1));
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE, Status.UNAVAILABLE));
        for (int i = 0; i < 3; i++) call(breaker);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerInterceptor.State.OPEN);

        int before = backendCalls.get();
        Status refused = call(breaker);

        assertThat(refused.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(refused.getDescription()).contains("Circuit breaker open for service 'orders'");
        assertThat(backendCalls.get()).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "GIVEN an open breaker WHEN the open duration passes and the trial call succeeds SHOULD close and serve normally again")
    void given_openBreaker_when_trialSucceeds_should_close() {
        CircuitBreakerInterceptor breaker = breaker(config(2, 1));
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE));
        call(breaker);
        call(breaker);
        assertThat(breaker.state()).isEqualTo(CircuitBreakerInterceptor.State.OPEN);

        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        assertThat(call(breaker).isOk()).isTrue();

        assertThat(breaker.state()).isEqualTo(CircuitBreakerInterceptor.State.CLOSED);
        assertThat(call(breaker).isOk()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a half-open breaker WHEN the trial call fails SHOULD open again and stop letting calls through")
    void given_halfOpen_when_trialFails_should_reopen() {
        CircuitBreakerInterceptor breaker = breaker(config(2, 1));
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE));
        call(breaker);
        call(breaker);

        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        outcomes.add(Status.UNAVAILABLE);
        assertThat(call(breaker).getCode()).isEqualTo(Status.Code.UNAVAILABLE);

        assertThat(breaker.state()).isEqualTo(CircuitBreakerInterceptor.State.OPEN);
        int before = backendCalls.get();
        assertThat(call(breaker).getDescription()).contains("Circuit breaker open");
        assertThat(backendCalls.get()).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "GIVEN half-open-calls of 1 WHEN several calls arrive at once SHOULD admit only the trial and refuse the rest")
    void given_halfOpen_when_manyCalls_should_admitOnlyTheTrial() {
        CircuitBreakerInterceptor breaker = breaker(config(2, 1));
        outcomes.addAll(List.of(Status.UNAVAILABLE, Status.UNAVAILABLE));
        call(breaker);
        call(breaker);
        nanos.addAndGet(Duration.ofSeconds(31).toNanos());

        int before = backendCalls.get();
        // The first of these is the trial and reaches the backend (and succeeds, closing the
        // breaker); had it not, the rest would still have been refused.
        call(breaker);
        assertThat(backendCalls.get()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName(
            "GIVEN a status the config does not count as a failure WHEN it keeps happening SHOULD leave the breaker closed")
    void given_nonFailureStatus_when_repeated_should_notOpen() {
        CircuitBreakerInterceptor breaker = breaker(config(2, 1));
        // A backend answering NOT_FOUND is working correctly; only the caller got it wrong.
        outcomes.addAll(List.of(Status.NOT_FOUND, Status.NOT_FOUND, Status.NOT_FOUND));

        for (int i = 0; i < 3; i++) call(breaker);

        assertThat(breaker.state()).isEqualTo(CircuitBreakerInterceptor.State.CLOSED);
        assertThat(backendCalls.get()).isEqualTo(3);
    }

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public java.io.InputStream stream(String value) {
            return new java.io.ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public String parse(java.io.InputStream stream) {
            try {
                return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
