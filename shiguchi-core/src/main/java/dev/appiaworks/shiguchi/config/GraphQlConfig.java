package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The generated GraphQL surface: whether it is served, and the limits and conversion behaviour
 * that apply to it. The schema itself comes from the same descriptors the REST routes do, shaped
 * by the {@code (shiguchi.graphql)} proto options.
 */
@Data
@NoArgsConstructor
public class GraphQlConfig {
    private boolean enabled = true;

    /**
     * Convert only the composite subtrees a query selected when turning a proto response into
     * the GraphQL result (scalar fields are always converted). Off: every response tree is
     * converted in full, the pre-0.2 behaviour.
     */
    private boolean selectionProjection = true;

    private FederationConfig federation = new FederationConfig();

    /** Guards against abusive queries. Off by default; see {@link LimitsConfig}. */
    private LimitsConfig limits = new LimitsConfig();

    /**
     * Shape of a backend failure in {@code errors[].extensions}. {@code GRPC}: the raw
     * {@code details} (proto3 JSON with {@code @type}). {@code NATIVE} (default): {@code code}
     * ({@code BAD_USER_INPUT}, {@code UNAUTHENTICATED}, {@code FORBIDDEN}, {@code NOT_FOUND},
     * {@code SERVICE_UNAVAILABLE}, {@code INTERNAL_SERVER_ERROR}) and, for a {@code BadRequest},
     * {@code fieldErrors:[{field, message, rule}]} with {@code field} as the GraphQL argument path;
     * other details stay under {@code details}. {@code grpcCode}, {@code errorType}, {@code service}
     * and {@code method} are kept in both.
     */
    private ErrorFormat errorFormat = ErrorFormat.NATIVE;

    public enum ErrorFormat {
        GRPC,
        NATIVE
    }

    /**
     * Who may run introspection queries ({@code __schema}, {@code __type}): everyone (the
     * default), authenticated callers only, or nobody. An operation that selects
     * {@code __schema} or {@code __type} while refused is answered with a GraphQL error and is not
     * run (graphql-java refuses the whole operation). Unlike
     * {@code spring.graphql.schema.introspection.enabled}, this is decided per request.
     */
    private Introspection introspection = Introspection.ENABLED;

    /**
     * {@code @defer}: a client that sends {@code Accept: multipart/mixed} gets the deferred parts of
     * its query as later parts of a multipart stream. Off by default.
     */
    private IncrementalConfig incremental = new IncrementalConfig();

    /**
     * Live queries: {@code query ... @live} runs again whenever the data behind it changes. Off by
     * default.
     */
    private LiveConfig live = new LiveConfig();

    /** Batched requests (a JSON array of operations in one POST); off by default. */
    private BatchConfig batch = new BatchConfig();

    /**
     * Keep parsed and validated query documents, keyed by the query text, so a repeated query skips
     * parsing and validation. Cleared whenever the schema reloads, since a validation result is
     * only true of the schema it ran against. Off by default.
     */
    private DocumentCacheConfig documentCache = new DocumentCacheConfig();

    /**
     * Automatic persisted queries: a client sends the SHA-256 of its query instead of the text, and
     * the text only once, on the first miss. Cuts request size for large queries and lets
     * {@code GET} carry them. Cleared on schema reload like the document cache. Off by default.
     */
    private PersistedQueriesConfig persistedQueries = new PersistedQueriesConfig();

    /**
     * Keep whole query responses for the TTL of the methods behind them ({@code cache_ttl_seconds}
     * on every root field's method), keyed by document, variables and, by default, the caller.
     * Mutations bypass it and evict the entries of the services they touch. Off by default; see
     * {@link ResponseCacheConfig}.
     */
    private ResponseCacheConfig responseCache = new ResponseCacheConfig();

    /** DataLoader batch size and opt-in cross-request entity caching; see {@link DataLoaderConfig}. */
    private DataLoaderConfig dataLoader = new DataLoaderConfig();

    /**
     * Proto option families from other gateways to read as aliases of the Shiguchi ones. Empty by
     * default: a proto carrying one of them logs a hint and is bound by convention. The only value
     * so far is {@code graphql-1079}, the {@code graphql.*} options (extension number 1079 on
     * method and field options), which map onto {@code (shiguchi.graphql)} and {@code (shiguchi.field)}.
     * The Shiguchi option wins when both are present.
     */
    private List<String> compatOptions = new ArrayList<>();

    /** Who may introspect the schema. */
    public enum Introspection {
        /** Anyone, the GraphQL default. */
        ENABLED,
        /** Only a request with an authenticated, non-anonymous principal. */
        AUTHENTICATED,
        /** Nobody. */
        DISABLED
    }

    /**
     * A POST to the GraphQL endpoint whose body is a JSON array runs each element as its own
     * operation, in order, and answers with an array of results in the same order. Each operation
     * is checked against the limits on its own and spends its own cost from the caller's budget,
     * so the batch is charged the sum.
     */
    @Data
    @NoArgsConstructor
    public static class BatchConfig {
        /** Most operations in one array. {@code 0} = batching off, an array body is refused. */
        private int maxOperations = 0;
    }

    /**
     * Incremental delivery ({@code @defer}) over {@code multipart/mixed}. On, the schema declares the
     * {@code @defer} directive; a client that does not accept {@code multipart/mixed} still gets the
     * complete JSON result. Not applied to responses served from the response cache, to batches or
     * to WebSocket operations. {@code @stream} is not supported by the engine yet.
     */
    @Data
    @NoArgsConstructor
    public static class IncrementalConfig {
        private boolean enabled = false;
    }

    /**
     * {@code query ... @live}: the operation runs, then again whenever a mutation through this
     * gateway touches a service behind its root fields, sending each new full result. WebSocket
     * ({@code graphql-transport-ws}) and SSE only; invalidation reaches this instance only.
     */
    @Data
    @NoArgsConstructor
    public static class LiveConfig {
        private boolean enabled = false;

        /** Least time between two runs of one live query; a larger {@code throttleMs} hint is kept. */
        private java.time.Duration minInterval = java.time.Duration.ofSeconds(1);

        /** Live queries one principal may run at once; the next is refused with {@code LIVE_LIMIT}. */
        private int maxPerPrincipal = 20;

        /** Live queries this instance runs at once. */
        private int maxTotal = 10000;

        /** A live query with no news is run again after this long, so a missed signal is repaired. Zero: never. */
        private java.time.Duration refreshInterval = java.time.Duration.ofMinutes(5);

        /** Live re-executions this instance runs at once; the rest wait in order. */
        private int maxConcurrentReexecutions = 32;
    }

    @Data
    @NoArgsConstructor
    public static class DocumentCacheConfig {
        private boolean enabled = false;

        /** Distinct query documents kept; the least recently used go first. */
        private long maxEntries = 2000;

        /** How long an unused document is kept. */
        private java.time.Duration expireAfterAccess = java.time.Duration.ofHours(1);
    }

    @Data
    @NoArgsConstructor
    public static class ResponseCacheConfig {
        private boolean enabled = false;

        /** Responses kept; the least recently used go first. */
        private long maxEntries = 2000;

        /**
         * How long past its TTL an entry is still served while one background refresh runs. Zero:
         * an expired entry is a miss and the caller waits for the backend.
         */
        private java.time.Duration staleWhileRevalidate = java.time.Duration.ZERO;

        /**
         * Put the caller's principal in the key, so two signed-in callers never share an entry.
         * Off, an entry is shared by everybody unless a root field's method carries
         * {@code auth_scopes}, whose entries are always per caller.
         */
        private boolean varyByPrincipal = true;
    }

    @Data
    @NoArgsConstructor
    public static class PersistedQueriesConfig {
        private boolean enabled = false;

        /** Registered queries kept; a client whose query was evicted simply sends it again. */
        private long maxEntries = 1000;

        /** How long an unused registered query is kept. */
        private java.time.Duration expireAfterAccess = java.time.Duration.ofHours(1);
    }
}
