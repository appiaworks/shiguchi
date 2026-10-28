package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GraphQlConfig;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The GraphQL document cache and automatic persisted queries, run through a real graphql-java
 * engine: what is parsed how often, what is registered, and that a schema reload forgets both.
 */
@DisplayName("GraphQL document cache and persisted queries")
class GatewayDocumentCacheTest {

    private static final GraphQLSchema SCHEMA = new SchemaGenerator()
            .makeExecutableSchema(
                    new SchemaParser().parse("type Query { hello: String, count: Int }"),
                    RuntimeWiring.newRuntimeWiring()
                            .type(
                                    "Query",
                                    t -> t.dataFetcher("hello", env -> "world").dataFetcher("count", env -> 3))
                            .build());

    private static GraphQlConfig config(boolean documents, boolean persisted) {
        GraphQlConfig config = new GraphQlConfig();
        config.getDocumentCache().setEnabled(documents);
        config.getPersistedQueries().setEnabled(persisted);
        return config;
    }

    /** An engine using the cache, counting how often graphql-java actually parses. */
    private static GraphQL engine(GatewayDocumentCache cache, AtomicInteger parses) {
        return GraphQL.newGraphQL(SCHEMA)
                .preparsedDocumentProvider((input, parseAndValidate) -> cache.getDocumentAsync(input, in -> {
                    parses.incrementAndGet();
                    return parseAndValidate.apply(in);
                }))
                .build();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static ExecutionInput persisted(String query, String hash) {
        return ExecutionInput.newExecutionInput()
                .query(query)
                .extensions(Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash)))
                .build();
    }

    @Test
    @DisplayName("GIVEN the document cache WHEN the same query runs twice SHOULD parse it once and answer both times")
    void given_documentCache_when_sameQueryTwice_should_parseOnce() {
        var cache = new GatewayDocumentCache(config(true, false));
        var parses = new AtomicInteger();
        GraphQL graphql = engine(cache, parses);

        ExecutionResult first = graphql.execute("{ hello }");
        ExecutionResult second = graphql.execute("{ hello }");

        assertThat(first.getErrors()).isEmpty();
        assertThat(second.<Map<String, Object>>getData()).containsEntry("hello", "world");
        assertThat(parses).hasValue(1);
        assertThat(cache.documentEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN the document cache WHEN different query texts run SHOULD keep one entry per text")
    void given_documentCache_when_differentQueries_should_keepSeparateEntries() {
        var cache = new GatewayDocumentCache(config(true, false));
        var parses = new AtomicInteger();
        GraphQL graphql = engine(cache, parses);

        graphql.execute("{ hello }");
        graphql.execute("{ count }");
        graphql.execute("{ hello count }");

        assertThat(parses).hasValue(3);
        assertThat(cache.documentEntries()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN a cached invalid query WHEN it runs again SHOULD report the same validation error without re-parsing")
    void given_invalidQuery_when_repeated_should_reportErrorFromCache() {
        var cache = new GatewayDocumentCache(config(true, false));
        var parses = new AtomicInteger();
        GraphQL graphql = engine(cache, parses);

        ExecutionResult first = graphql.execute("{ nope }");
        ExecutionResult second = graphql.execute("{ nope }");

        assertThat(first.getErrors()).isNotEmpty();
        assertThat(second.getErrors()).isNotEmpty();
        assertThat(second.getErrors().get(0).getMessage()).contains("nope");
        assertThat(parses).hasValue(1);
    }

    @Test
    @DisplayName("GIVEN cached documents WHEN the schema reloads SHOULD forget them and parse again")
    void given_cachedDocuments_when_schemaReloads_should_parseAgain() {
        var cache = new GatewayDocumentCache(config(true, false));
        var parses = new AtomicInteger();
        GraphQL graphql = engine(cache, parses);
        graphql.execute("{ hello }");

        cache.onSchemaReloaded();
        graphql.execute("{ hello }");

        assertThat(parses).hasValue(2);
    }

    @Test
    @DisplayName(
            "GIVEN persisted queries WHEN an unknown hash arrives without text, then with text, then alone SHOULD ask once, register, then run by hash")
    void given_persistedQueries_when_apqExchange_should_registerThenRunByHash() throws Exception {
        var cache = new GatewayDocumentCache(config(false, true));
        GraphQL graphql = engine(cache, new AtomicInteger());
        String query = "{ hello }";
        String hash = sha256(query);

        ExecutionResult unknown = graphql.execute(persisted("", hash));
        ExecutionResult registered = graphql.execute(persisted(query, hash));
        ExecutionResult byHash = graphql.execute(persisted("", hash));

        assertThat(unknown.getErrors()).hasSize(1);
        assertThat(unknown.getErrors().get(0).getMessage()).containsIgnoringCase("PersistedQueryNotFound");
        assertThat(registered.<Map<String, Object>>getData()).containsEntry("hello", "world");
        assertThat(byHash.getErrors()).isEmpty();
        assertThat(byHash.<Map<String, Object>>getData()).containsEntry("hello", "world");
        assertThat(cache.persistedEntries()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN persisted queries WHEN a query with a validation error is sent with its hash SHOULD not register it")
    void given_persistedQueries_when_invalidQuery_should_notRegister() throws Exception {
        var cache = new GatewayDocumentCache(config(false, true));
        GraphQL graphql = engine(cache, new AtomicInteger());
        String query = "{ nope }";

        ExecutionResult result = graphql.execute(persisted(query, sha256(query)));

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(cache.persistedEntries()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN a registered persisted query WHEN the schema reloads SHOULD forget it, so the hash alone is unknown again")
    void given_registeredQuery_when_schemaReloads_should_forgetIt() throws Exception {
        var cache = new GatewayDocumentCache(config(false, true));
        GraphQL graphql = engine(cache, new AtomicInteger());
        String hash = sha256("{ hello }");
        graphql.execute(persisted("{ hello }", hash));

        cache.onSchemaReloaded();
        ExecutionResult byHash = graphql.execute(persisted("", hash));

        assertThat(cache.persistedEntries()).isZero();
        assertThat(byHash.getErrors().get(0).getMessage()).containsIgnoringCase("PersistedQueryNotFound");
    }
}
