package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The generated GraphQL surface: whether it is served, and the limits and conversion behaviour
 * that apply to it. The schema itself comes from the same descriptors the REST routes do, shaped
 * by the {@code (shiguchi.api.graphql)} proto options.
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

    /** DataLoader batch size and opt-in cross-request entity caching; see {@link DataLoaderConfig}. */
    private DataLoaderConfig dataLoader = new DataLoaderConfig();

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
    public static class PersistedQueriesConfig {
        private boolean enabled = false;

        /** Registered queries kept; a client whose query was evicted simply sends it again. */
        private long maxEntries = 1000;

        /** How long an unused registered query is kept. */
        private java.time.Duration expireAfterAccess = java.time.Duration.ofHours(1);
    }
}
