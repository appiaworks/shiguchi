package dev.appiaworks.shiguchi.grpc;

import com.google.common.util.concurrent.RateLimiter;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GrpcSecurityConfig;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 *  - With {@code shiguchi.grpc.security.mode: ENFORCE}: asks the {@link ProxyCallGuard} before
 *    the call starts (missing or bad bearer: UNAUTHENTICATED; denied: PERMISSION_DENIED;
 *    throttled: RESOURCE_EXHAUSTED), carries the authenticated principal to the bridge, and ends
 *    the call with UNAUTHENTICATED when the token expires. Services the gateway hosts itself
 *    ({@link GatewayHostedService}) authenticate their own callers and are not guarded here.
 *
 * Metadata passthrough (authorization, correlation IDs) is handled directly in
 * GrpcProxyHandlerRegistry by merging the inbound Metadata into the outbound ClientCall
 * headers; it replaces any inbound x-principal with "anonymous".
 */
@Component
public class GrpcProxyServerInterceptor implements ServerInterceptor, org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GrpcProxyServerInterceptor.class);

    static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /** The authenticated caller's name, set on the call's context in ENFORCE mode only. */
    static final Context.Key<String> PRINCIPAL = Context.key("shiguchi-principal");

    /** Cancellation cause of a call's context when its token expired. */
    static final Throwable TOKEN_EXPIRED =
            Status.UNAUTHENTICATED.withDescription("The bearer token expired").asRuntimeException();

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final ProxyCallGuard guard;
    private volatile Set<String> hostedServiceNames = Set.of();
    private volatile ScheduledExecutorService expiryTimer;

    public GrpcProxyServerInterceptor(GatewayProperties properties) {
        this(properties, (ProxyCallGuard) null);
    }

    /**
     * The constructor Spring uses.
     *
     * @throws IllegalStateException when {@code shiguchi.grpc.security.mode} is {@code ENFORCE} and
     *     no {@link ProxyCallGuard} bean exists
     */
    @Autowired
    public GrpcProxyServerInterceptor(GatewayProperties properties, ObjectProvider<ProxyCallGuard> guards) {
        this(properties, guards.getIfAvailable());
    }

    GrpcProxyServerInterceptor(GatewayProperties properties, ProxyCallGuard guard) {
        this.properties = properties;
        this.guard = guard;
        if (enforcing() && guard == null) {
            throw new IllegalStateException("shiguchi.grpc.security.mode is ENFORCE but no ProxyCallGuard bean "
                    + "exists. Use the Spring Boot starter (it provides one), or define a ProxyCallGuard bean.");
        }
    }

    private boolean enforcing() {
        return properties.getGrpc().getSecurity().getMode() == GrpcSecurityConfig.Mode.ENFORCE;
    }

    /** Names of the services the gateway implements itself on this port; called when the server starts. */
    void hostedServices(Set<String> serviceNames) {
        this.hostedServiceNames = Set.copyOf(serviceNames);
    }

    @Override
    public void destroy() {
        ScheduledExecutorService timer = expiryTimer;
        if (timer != null) timer.shutdownNow();
    }

    private ScheduledExecutorService timer() {
        ScheduledExecutorService timer = expiryTimer;
        if (timer == null) {
            synchronized (this) {
                timer = expiryTimer;
                if (timer == null) {
                    ScheduledThreadPoolExecutor created = new ScheduledThreadPoolExecutor(1, r -> {
                        Thread t = new Thread(r, "shiguchi-grpc-token-expiry");
                        t.setDaemon(true);
                        return t;
                    });
                    created.setRemoveOnCancelPolicy(true);
                    expiryTimer = timer = created;
                }
            }
        }
        return timer;
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

        if (!enforcing() || isHosted(fullMethod)) {
            return next.startCall(call, headers);
        }

        ProxyCallGuard.Decision decision;
        try {
            decision = guard.admit(fullMethod, headers);
        } catch (RuntimeException e) {
            log.warn("gRPC proxy guard failed for {}; denying the call", fullMethod, e);
            decision = ProxyCallGuard.Decision.deny(Status.PERMISSION_DENIED.withDescription("Denied"));
        }
        if (decision == null || !decision.allowed()) {
            Status status = decision != null ? decision.denial() : Status.PERMISSION_DENIED;
            Metadata trailers = decision != null && decision.trailers() != null ? decision.trailers() : new Metadata();
            call.close(status, trailers);
            return new ServerCall.Listener<>() {};
        }

        Context.CancellableContext context =
                Context.current().withValue(PRINCIPAL, decision.principal()).withCancellation();
        Instant expiresAt = decision.expiresAt();
        if (expiresAt != null) {
            long millis = Duration.between(Instant.now(), expiresAt).toMillis();
            if (millis <= 0) {
                call.close(Status.UNAUTHENTICATED.withDescription("The bearer token expired"), new Metadata());
                return new ServerCall.Listener<>() {};
            }
            ScheduledFuture<?> task =
                    timer().schedule(() -> context.cancel(TOKEN_EXPIRED), millis, TimeUnit.MILLISECONDS);
            context.addListener(c -> task.cancel(false), Runnable::run);
        }
        return Contexts.interceptCall(context, call, headers, next);
    }

    private boolean isHosted(String fullMethod) {
        int slash = fullMethod.lastIndexOf('/');
        return slash > 0 && hostedServiceNames.contains(fullMethod.substring(0, slash));
    }

    private static String extractCallerHint(Metadata headers) {
        String auth = headers.get(AUTHORIZATION_KEY);
        return auth != null ? "bearer:***" : "anonymous";
    }
}
