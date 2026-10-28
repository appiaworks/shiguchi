package dev.appiaworks.shiguchi.grpc;

import com.google.common.util.concurrent.RateLimiter;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Server interceptor for all incoming gRPC proxy calls.
 *
 * Responsibilities:
 *  - Rate limiting: per-method token bucket (Guava RateLimiter). Rejects with
 *    RESOURCE_EXHAUSTED immediately when the bucket is empty. Keyed by full method
 *    name so each RPC gets its own limit.
 *  - Request logging: method + caller identity at DEBUG level.
 *
 * Metadata passthrough (authorization, correlation IDs) is handled directly in
 * GrpcProxyHandlerRegistry by merging the inbound Metadata into the outbound ClientCall
 * headers; it replaces any inbound x-principal with "anonymous".
 */
@Component
public class GrpcProxyServerInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcProxyServerInterceptor.class);

    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    public GrpcProxyServerInterceptor(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String fullMethod = call.getMethodDescriptor().getFullMethodName();

        if (properties.getGrpc().getRateLimit().isEnabled()) {
            RateLimiter limiter = rateLimiters.computeIfAbsent(
                    fullMethod,
                    k -> RateLimiter.create(properties.getGrpc().getRateLimit().getRequestsPerSecond()));
            if (!limiter.tryAcquire()) {
                call.close(
                        Status.RESOURCE_EXHAUSTED.withDescription("Rate limit exceeded for " + fullMethod),
                        new Metadata());
                return new ServerCall.Listener<>() {};
            }
        }

        log.debug("gRPC proxy: {} caller={}", fullMethod, extractCallerHint(headers));

        return next.startCall(call, headers);
    }

    private static String extractCallerHint(Metadata headers) {
        String auth = headers.get(AUTHORIZATION_KEY);
        return auth != null ? "bearer:***" : "anonymous";
    }
}
