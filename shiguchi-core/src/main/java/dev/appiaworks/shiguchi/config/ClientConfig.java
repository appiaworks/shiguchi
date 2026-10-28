package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client-side channel tuning for the {@code ManagedChannel}s the gateway opens to each
 * downstream gRPC service. These are <b>outbound</b> settings (gateway → backend) and are
 * deliberately separate from {@link GrpcConfig}, which configures the gateway's own
 * inbound gRPC server.
 *
 * <p>One channel is created per downstream service and reused for all calls — gRPC
 * multiplexes every RPC over the channel's HTTP/2 connection, so no external connection
 * pool is needed. These settings keep that long-lived channel healthy and bounded.
 *
 * <p>Defaults are chosen to be safe in shared/proxied networks:
 * <ul>
 *   <li>Keepalive pings flow only during active RPCs ({@code keepAliveWithoutCalls=false}),
 *       which avoids tripping a backend's {@code GOAWAY: too_many_pings} guard.</li>
 *   <li>{@code maxInboundMessageSize} keeps gRPC's 4&nbsp;MiB default so a misbehaving
 *       backend can't balloon gateway memory; raise it explicitly when needed.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class ClientConfig {
    /**
     * Interval between keepalive HTTP/2 PING frames sent to downstream services.
     * A liveness probe that detects half-open connections dropped silently by
     * load balancers, NATs, or proxies before the next RPC fails with UNAVAILABLE.
     */
    private java.time.Duration keepAliveTime = java.time.Duration.ofSeconds(30);

    /** How long to wait for a keepalive PING ack before considering the connection dead. */
    private java.time.Duration keepAliveTimeout = java.time.Duration.ofSeconds(5);

    /**
     * When true, send keepalive pings even on channels with no active RPCs.
     * Left false by default: pinging an idle channel risks the backend's
     * {@code GOAWAY: too_many_pings} enforcement. Enable only when backends are
     * configured to permit it.
     */
    private boolean keepAliveWithoutCalls = false;

    /**
     * Close a channel's transport after this idle period (no active RPCs), reconnecting
     * lazily on the next call. Zero/negative leaves gRPC's default (30 minutes).
     */
    private java.time.Duration idleTimeout = java.time.Duration.ZERO;

    /** Max inbound message size in bytes for downstream responses. Default 4 MiB (gRPC default). */
    private int maxInboundMessageSize = 4 * 1024 * 1024;

    /**
     * How often a {@code discovery-name} channel re-reads the instance list from the
     * {@code DiscoveryClient}. gRPC also asks for a refresh after a connection failure, so
     * a dead instance is dropped sooner than this. Default 30 s; minimum 100 ms.
     */
    private java.time.Duration discoveryRefresh = java.time.Duration.ofSeconds(30);

    /**
     * Default deadline applied to every outbound <b>unary</b> call. A request that has not
     * completed within this window fails with {@code DEADLINE_EXCEEDED} (mapped to HTTP 504)
     * instead of holding gateway resources indefinitely. Server-streaming calls are exempt —
     * a deadline would cap the lifetime of intentionally long-lived streams.
     *
     * <p>{@code Duration.ZERO} (the default) disables the deadline. Override per service
     * with {@code services[].deadline}.
     */
    private java.time.Duration defaultDeadline = java.time.Duration.ZERO;

    /**
     * Timeout for calls to {@code REST_DOWNSTREAM} (OpenAPI) backends when nothing more specific is
     * set. Default 30 s; must be positive. It bounds both the REST passthrough (as the route's
     * {@code response-timeout}) and the GraphQL / merge dispatch. A positive per-service
     * {@code deadline} or {@link #defaultDeadline} wins over it, and a per-operation
     * {@code operation-timeouts} entry wins over both.
     */
    private java.time.Duration httpTimeout = java.time.Duration.ofSeconds(30);

    /**
     * Additional inbound HTTP request headers copied verbatim onto the outbound gRPC
     * metadata of every call (case-insensitive match, lowercase metadata keys).
     * {@code authorization} and {@code x-principal} are always propagated regardless of
     * this list. Typical entries: {@code x-request-id}, {@code x-correlation-id},
     * {@code x-tenant-id}.
     */
    private List<String> forwardHeaders = new ArrayList<>();

    /**
     * Forward the well-known distributed-tracing headers from the inbound HTTP request
     * to the gRPC backend: {@code traceparent}, {@code tracestate}, {@code b3},
     * {@code x-b3-*}, {@code x-request-id}, {@code x-amzn-trace-id},
     * {@code x-cloud-trace-context}. On by default — it keeps traces intact even
     * without an OpenTelemetry SDK on the gateway (when an {@code OpenTelemetry} bean
     * is present, the gRPC OTel plugin additionally creates proper client spans).
     */
    private boolean forwardTracingHeaders = true;
}
