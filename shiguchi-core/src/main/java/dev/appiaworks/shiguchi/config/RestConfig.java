package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The transcoded REST surface — the routes generated from {@code google.api.http} options —
 * plus the request limits and pagination defaults that apply to them.
 */
@Data
@NoArgsConstructor
public class RestConfig {
    private boolean enabled = true;

    /**
     * Give every method without a {@code google.api.http} rule a REST route anyway:
     * {@code POST /{package.Service}/{Method}} with the whole JSON body as the request message —
     * the gRPC path itself, so it cannot collide with an annotated route. Off by default,
     * because it makes methods reachable over REST that were not before; include/exclude-methods
     * still apply. A service's {@code auto-mapping} overrides this.
     */
    private boolean autoMapping = false;

    private PaginationConfig pagination = new PaginationConfig();

    /** How server-streaming routes frame their messages. */
    private StreamingConfig streaming = new StreamingConfig();

    /**
     * Framing of a server-streaming route's response. The caller's {@code Accept} header picks
     * per request — {@code text/event-stream} for Server-Sent Events, {@code application/x-ndjson}
     * or {@code application/json} for NDJSON — and {@link #defaultFormat} applies when it names
     * neither (no header, or {@code *}{@code /*}).
     */
    @Data
    @NoArgsConstructor
    public static class StreamingConfig {
        private StreamFormat defaultFormat = StreamFormat.NDJSON;

        /**
         * On an SSE response, send a comment ({@code : keepalive}) after this long without an
         * event, so proxies and load balancers do not close a quiet stream as idle. Restarts on
         * every event. {@code 0} turns it off. NDJSON has no comment syntax and gets none.
         */
        private java.time.Duration sseKeepalive = java.time.Duration.ofSeconds(15);
    }

    public enum StreamFormat {
        /** {@code application/x-ndjson}: one JSON message per line. */
        NDJSON,
        /** {@code text/event-stream}: one {@code data:} event per message, readable by a browser {@code EventSource}. */
        SSE
    }

    /**
     * Largest request body the REST surface accepts on a transcoded route, JSON or codec
     * ({@code 4MB} default, the same cap gRPC puts on a message). A larger body — declared by
     * {@code Content-Length} or discovered while reading — is rejected with HTTP 413 and
     * {@code RESOURCE_EXHAUSTED} before anything is parsed or sent to a backend. GraphQL
     * requests are bounded by {@code spring.codec.max-in-memory-size} instead.
     */
    private org.springframework.util.unit.DataSize maxRequestBody =
            org.springframework.util.unit.DataSize.ofMegabytes(4);

    /** How strictly the shape of a transcoded request is checked before it is sent on. */
    private RequestValidationConfig requestValidation = new RequestValidationConfig();

    /**
     * Checks on the HTTP request itself — which query parameters it carries — as opposed to the
     * message it builds, which {@code shiguchi-validation} checks against protovalidate rules.
     */
    @Data
    @NoArgsConstructor
    public static class RequestValidationConfig {
        /**
         * Answer HTTP 400 ({@code INVALID_ARGUMENT}, with a {@code google.rpc.BadRequest} naming
         * each offender) when a query parameter matches no field of the request message. Off by
         * default: such a parameter is silently ignored, which is what a typo like
         * {@code ?pageSzie=} does today. A service's {@code reject-unknown-query-parameters}
         * overrides this.
         */
        private boolean rejectUnknownQueryParameters = false;

        /**
         * Query parameters that are never reported as unknown, e.g. {@code access_token} or a
         * cache buster. The gateway's own parameters ({@code pageSize}, {@code pageToken},
         * {@code first}, {@code after}, {@code sort}, {@code fields}) never need listing.
         */
        private java.util.List<String> ignoredQueryParameters = new java.util.ArrayList<>();

        /**
         * Answer HTTP 400 ({@code BadRequest} reason {@code BINDING_CONFLICT}) when a query
         * parameter or path variable sets a field to a different value than the request body
         * (or a query parameter and a path variable disagree). Off by default: the path wins,
         * then the query, silently. The same value in two places is always accepted, since
         * AIP-134 style updates repeat the resource name in the path and the body.
         */
        private boolean rejectConflictingValues = false;

        /**
         * Accept enum names in any case — {@code ?color=red} and {@code {"color":"Red"}} for a
         * value declared {@code RED} — in query parameters, path variables and JSON bodies. Off by
         * default: proto3 JSON enum names are case-sensitive.
         */
        private boolean caseInsensitiveEnums = false;
    }

    /**
     * Names of the HTTP response headers carrying pagination metadata extracted from a
     * response message's {@code page_info} field. Rename them to match an existing API
     * convention (e.g. {@code Link}-style gateways or corporate header schemes).
     */
    @Data
    @NoArgsConstructor
    public static class PaginationConfig {
        /** Header carrying {@code page_info.next_page_token} (omitted on the last page). */
        private String nextPageTokenHeader = "X-Next-Page-Token";
        /** Header carrying {@code page_info.total_count} (omitted when the server did not compute it). */
        private String totalCountHeader = "X-Total-Count";
    }

    /**
     * Largest response body the gateway sends over HTTP, as written - JSON, a codec's bytes, raw
     * {@code google.api.HttpBody} data, a merged page, or the running total of a stream (downloads
     * and gRPC-Web data frames included). Unset (the default) means no cap beyond each backend
     * message's {@code max-inbound-message-size}. Over the cap, HTTP 502 with
     * {@code RESOURCE_EXHAUSTED} and an {@code ErrorInfo} reason {@code RESPONSE_TOO_LARGE}; a
     * stream already under way ends with that error in-band, a download already under way is
     * aborted, and gRPC-Web carries it in the trailers.
     */
    private org.springframework.util.unit.DataSize maxResponseBody;

    /**
     * Backend response headers and trailers copied onto the HTTP response of a transcoded call,
     * by name (case-insensitive), or {@code *} for all of them. Empty (the default) forwards none.
     * Headers arrive with the response and trailers when the call ends, so a unary or
     * client-streaming route forwards both, while a streamed response has committed its headers
     * by then and forwards the backend's headers only. Never forwarded, whatever the list says:
     * {@code grpc-*} and pseudo-headers, binary ({@code -bin}) metadata, hop-by-hop and framing
     * headers ({@code content-type}, {@code content-length}, ...), and any header the gateway
     * set on the response itself. Merge routes, which call several backends, forward none.
     * {@code services[].forward-response-headers} overrides this per service.
     */
    private java.util.List<String> forwardResponseHeaders = new java.util.ArrayList<>();

    /**
     * Honour a {@code grpc-timeout} request header (gRPC's format: digits and a unit, {@code 500m},
     * {@code 2S}) as the backend call's deadline, as gRPC-Web calls already do. A unary call keeps
     * the shorter of it and the service's {@code deadline}; a stream, which has no configured
     * deadline, gets the client's. It can only shorten a call, so it is on by default; a malformed
     * value is ignored.
     */
    private boolean honorGrpcTimeout = true;

    /** The gateway's own cache of REST responses, for routes with a cache policy. Off by default. */
    private ResponseCacheConfig responseCache = new ResponseCacheConfig();

    /**
     * Let identical concurrent {@code GET}s of a unary route share one backend call: the second
     * and later callers wait for the first one's answer instead of sending their own. Identical
     * means the same method, arguments, response format and forwarded caller identity, so no
     * caller ever receives another caller's answer. Off by default. The response cache coalesces
     * its misses whether or not this is on.
     */
    private boolean coalesceRequests = false;

    /** Compression of REST responses by the gateway. Off by default. */
    private CompressionConfig compression = new CompressionConfig();

    /**
     * Map a resource's {@code etag} field (AIP-154) to HTTP: a response whose message has a
     * top-level string field {@code etag} sends it as the {@code ETag} header (and answers a
     * matching {@code If-None-Match} with 304); an {@code If-Match} header fills the request's
     * {@code etag} field (top level, or in the message the route's {@code body} names), and the
     * backend's {@code ABORTED} or {@code FAILED_PRECONDITION} answer to such a request becomes
     * HTTP 412. Off by default.
     */
    private boolean resourceEtags = false;

    /**
     * The response cache ({@code rest.response-cache}): unary {@code GET} routes whose method has a
     * cache policy — a {@code services[].cache} rule or a proto {@code cache_ttl_seconds} — are
     * answered from memory while fresh, and the backend is called once per key however many
     * callers miss at the same moment.
     *
     * <p>An entry is keyed by the method, its path and query arguments, the response format and,
     * unless the policy is {@code PUBLIC}, the caller's forwarded identity (principal,
     * {@code Authorization} and {@code client.forward-headers}). It is looked up after
     * authorization, so a caller who may not call the method never reads it. Only 200 responses
     * are kept, never one that sets a cookie. A successful {@code POST}, {@code PUT},
     * {@code PATCH} or {@code DELETE} on the same path evicts it, and a schema reload clears the
     * cache.
     */
    @Data
    @NoArgsConstructor
    public static class ResponseCacheConfig {
        private boolean enabled = false;

        /** Total size of the bodies kept, as written; the least recently used go first. */
        private org.springframework.util.unit.DataSize maxSize = org.springframework.util.unit.DataSize.ofMegabytes(64);

        /** A body larger than this is served but not kept. */
        private org.springframework.util.unit.DataSize maxEntrySize =
                org.springframework.util.unit.DataSize.ofMegabytes(1);
    }

    /**
     * Gateway-side compression of REST responses ({@code rest.compression}). The gateway compresses
     * the unary bodies it writes, so the {@code ETag} can name the encoded form and the response
     * cache keeps the compressed bytes instead of compressing on every hit. Streams, downloads and
     * gRPC-Web are left alone. Use this instead of {@code server.compression}, which compresses
     * after the gateway has tagged the body.
     */
    @Data
    @NoArgsConstructor
    public static class CompressionConfig {
        private boolean enabled = false;

        /** Bodies smaller than this are sent as they are; compressing them costs more than it saves. */
        private org.springframework.util.unit.DataSize minSize = org.springframework.util.unit.DataSize.ofKilobytes(1);

        /**
         * Content types worth compressing. A {@code type/*} entry covers the whole type, and every
         * {@code +json} type is covered when {@code application/json} is listed.
         */
        private java.util.List<String> mimeTypes =
                new java.util.ArrayList<>(java.util.List.of("application/json", "text/*"));
    }
}
