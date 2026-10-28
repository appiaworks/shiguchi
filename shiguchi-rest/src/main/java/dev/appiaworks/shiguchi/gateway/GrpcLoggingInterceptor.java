package dev.appiaworks.shiguchi.gateway;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC client-side logging interceptor.
 *
 * Log levels:
 *   DEBUG — [gRPC→] method start, [gRPC←] method end with status and duration
 *   TRACE — [gRPC req] outbound message body (JSON), [gRPC res] inbound message body (JSON)
 *
 * Enable per logger:
 *   logging.level.dev.appiaworks.shiguchi.gateway.GrpcLoggingInterceptor: DEBUG   # before/after
 *   logging.level.dev.appiaworks.shiguchi.gateway.GrpcLoggingInterceptor: TRACE   # + message bodies
 */
@Component
public class GrpcLoggingInterceptor implements ClientInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcLoggingInterceptor.class);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        String fullMethod = method.getFullMethodName();
        long startNanos = System.nanoTime();

        log.debug("[gRPC→] {}", fullMethod);

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {

            @Override
            public void sendMessage(ReqT message) {
                if (log.isTraceEnabled() && message instanceof Message m) {
                    try {
                        log.trace(
                                "[gRPC req] {} body={}",
                                fullMethod,
                                JsonFormat.printer().print(m));
                    } catch (Exception ignored) {
                    }
                }
                super.sendMessage(message);
            }

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(
                        new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {

                            @Override
                            public void onMessage(RespT message) {
                                if (log.isTraceEnabled() && message instanceof Message m) {
                                    try {
                                        log.trace(
                                                "[gRPC res] {} body={}",
                                                fullMethod,
                                                JsonFormat.printer().print(m));
                                    } catch (Exception ignored) {
                                    }
                                }
                                super.onMessage(message);
                            }

                            @Override
                            public void onClose(Status status, Metadata trailers) {
                                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                                if (status.isOk()) {
                                    log.debug("[gRPC←] {} OK ({}ms)", fullMethod, ms);
                                } else {
                                    log.debug(
                                            "[gRPC←] {} {} ({}ms) desc={}",
                                            fullMethod,
                                            status.getCode(),
                                            ms,
                                            status.getDescription());
                                }
                                super.onClose(status, trailers);
                            }
                        },
                        headers);
            }
        };
    }
}
