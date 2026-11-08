package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GraphQlConfig;
import dev.appiaworks.shiguchi.graphql.GraphQlResponseCache.Outcome;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition.GraphQlOperationType;
import graphql.ExecutionResultImpl;
import graphql.GraphqlErrorBuilder;
import io.grpc.MethodDescriptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Mono;

class GraphQlResponseCacheInterceptorTest {

    private static final String STORE = "lim.Store";
    private static final String ITEM_QUERY = "query Item($id: String!) { item(id: $id) { id name } }";

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final TestClock clock = new TestClock();
    private final GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
    private final GraphQlConfig.ResponseCacheConfig config = new GraphQlConfig.ResponseCacheConfig();
    private final AtomicInteger executions = new AtomicInteger();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private GraphQlResponseCache cache;
    private GraphQlResponseCacheInterceptor interceptor;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        when(registry.findByGraphQlName(anyString())).thenReturn(List.of());
        when(registry.findByGraphQlName("item")).thenReturn(List.of(route("GetItem", GraphQlOperationType.QUERY, 30)));
        when(registry.findByGraphQlName("listItems"))
                .thenReturn(List.of(route("ListItems", GraphQlOperationType.QUERY, 0)));
        when(registry.findByGraphQlName("updateItem"))
                .thenReturn(List.of(route("UpdateItem", GraphQlOperationType.MUTATION, 0)));
        when(registry.findByGraphQlName("ping"))
                .thenReturn(List.of(
                        RouteDefinition.builder("lim.Other", "Ping", MethodDescriptor.MethodType.UNARY, "localhost:1")
                                .graphQl("ping", GraphQlOperationType.MUTATION)
                                .build()));
        rebuild();
    }

    private void rebuild() {
        cache = new GraphQlResponseCache(config, registry, meters, clock);
        interceptor = new GraphQlResponseCacheInterceptor(cache);
    }

    private static RouteDefinition route(String method, GraphQlOperationType type, int ttl) {
        return RouteDefinition.builder(STORE, method, MethodDescriptor.MethodType.UNARY, "localhost:1")
                .graphQl(Character.toLowerCase(method.charAt(0)) + method.substring(1), type)
                .cacheTtlSeconds(ttl)
                .build();
    }

    private final WebGraphQlInterceptor.Chain chain = request -> Mono.fromSupplier(() -> {
        int n = executions.incrementAndGet();
        return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(
                request.toExecutionInput(),
                ExecutionResultImpl.newExecutionResult()
                        .data(Map.of("item", Map.of("id", "1", "name", "run " + n)))
                        .build()));
    });

    private final WebGraphQlInterceptor.Chain failingChain = request -> Mono.fromSupplier(() -> {
        executions.incrementAndGet();
        return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(
                request.toExecutionInput(),
                ExecutionResultImpl.newExecutionResult()
                        .addError(GraphqlErrorBuilder.newError()
                                .message("UNAVAILABLE")
                                .build())
                        .build()));
    });

    private static WebGraphQlRequest http(String query, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (variables != null) body.put("variables", variables);
        return new WebGraphQlRequest(
                URI.create("http://gw/graphql"),
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of(),
                body,
                "1",
                Locale.ENGLISH);
    }

    private static WebGraphQlRequest webSocket(String query) {
        return new WebSocketGraphQlRequest(
                URI.create("ws://gw/graphql/ws"),
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of(),
                Map.of("query", query),
                "1",
                Locale.ENGLISH,
                mock(WebSocketSessionInfo.class));
    }

    private WebGraphQlResponse run(WebGraphQlInterceptor.Chain chain, WebGraphQlRequest request, String user) {
        Mono<WebGraphQlResponse> mono = interceptor.intercept(request, chain);
        if (user != null) {
            mono = mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                    new TestingAuthenticationToken(user, "n/a", "ROLE_USER")));
        }
        return mono.block(Duration.ofSeconds(5));
    }

    private WebGraphQlResponse run(WebGraphQlRequest request) {
        return run(chain, request, "alice");
    }

    @SuppressWarnings("unchecked")
    private static String name(WebGraphQlResponse response) {
        Map<String, Object> data = response.getData();
        return (String) ((Map<String, Object>) data.get("item")).get("name");
    }

    private static String cacheStatus(WebGraphQlResponse response) {
        return response.getResponseHeaders().getFirst(GraphQlResponseCacheInterceptor.CACHE_STATUS);
    }

    @Test
    @DisplayName("GIVEN a query whose root field has a TTL WHEN it is sent twice SHOULD execute once and serve a hit")
    void given_cacheable_query_when_sent_twice_should_execute_once_and_serve_hit() {
        WebGraphQlResponse first = run(http(ITEM_QUERY, Map.of("id", "1")));
        WebGraphQlResponse second = run(http(ITEM_QUERY, Map.of("id", "1")));

        assertThat(executions).hasValue(1);
        assertThat(cacheStatus(first)).isEqualTo("shiguchi; fwd=miss; stored");
        assertThat(cacheStatus(second)).isEqualTo("shiguchi; hit");
        assertThat(second.getResponseHeaders().getFirst(GraphQlResponseCacheInterceptor.AGE))
                .isEqualTo("0");
        assertThat(name(second)).isEqualTo("run 1");
        assertThat(cache.count(Outcome.MISS)).isEqualTo(1);
        assertThat(cache.count(Outcome.HIT)).isEqualTo(1);
        assertThat(meters.get(GraphQlResponseCache.METER)
                        .tag("result", "hit")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("GIVEN a cached entry WHEN its TTL has passed SHOULD execute again")
    void given_cached_entry_when_ttl_passed_should_execute_again() {
        run(http(ITEM_QUERY, Map.of("id", "1")));
        clock.advance(Duration.ofSeconds(31));

        WebGraphQlResponse response = run(http(ITEM_QUERY, Map.of("id", "1")));

        assertThat(executions).hasValue(2);
        assertThat(cacheStatus(response)).isEqualTo("shiguchi; fwd=miss; stored");
        assertThat(name(response)).isEqualTo("run 2");
    }

    @Test
    @DisplayName(
            "GIVEN stale-while-revalidate WHEN an expired entry is asked for twice SHOULD serve stale and refresh once")
    void given_stale_while_revalidate_when_expired_entry_asked_twice_should_serve_stale_and_refresh_once() {
        config.setStaleWhileRevalidate(Duration.ofSeconds(60));
        rebuild();
        run(http(ITEM_QUERY, Map.of("id", "1")));
        clock.advance(Duration.ofSeconds(45));

        WebGraphQlResponse stale = run(http(ITEM_QUERY, Map.of("id", "1")));

        assertThat(cacheStatus(stale)).isEqualTo("shiguchi; hit; detail=stale");
        assertThat(stale.getResponseHeaders().getFirst(GraphQlResponseCacheInterceptor.AGE))
                .isEqualTo("45");
        assertThat(name(stale)).isEqualTo("run 1");
        // the refresh ran synchronously on this Mono and replaced the entry
        assertThat(executions).hasValue(2);
        assertThat(cache.count(Outcome.STALE)).isEqualTo(1);

        WebGraphQlResponse fresh = run(http(ITEM_QUERY, Map.of("id", "1")));
        assertThat(cacheStatus(fresh)).isEqualTo("shiguchi; hit");
        assertThat(name(fresh)).isEqualTo("run 2");
        assertThat(executions).hasValue(2);
    }

    @Test
    @DisplayName("GIVEN an expired entry past stale-while-revalidate WHEN asked SHOULD be a miss")
    void given_entry_past_stale_window_when_asked_should_be_miss() {
        config.setStaleWhileRevalidate(Duration.ofSeconds(10));
        rebuild();
        run(http(ITEM_QUERY, Map.of("id", "1")));
        clock.advance(Duration.ofSeconds(41));

        WebGraphQlResponse response = run(http(ITEM_QUERY, Map.of("id", "1")));

        assertThat(cacheStatus(response)).startsWith("shiguchi; fwd=miss");
        assertThat(executions).hasValue(2);
    }

    @Test
    @DisplayName("GIVEN a cached query WHEN a mutation on the same service runs SHOULD evict it")
    void given_cached_query_when_mutation_on_same_service_runs_should_evict() {
        run(http(ITEM_QUERY, Map.of("id", "1")));

        run(http("mutation { updateItem(id: \"1\", name: \"x\") { id } }", null));
        WebGraphQlResponse after = run(http(ITEM_QUERY, Map.of("id", "1")));

        assertThat(executions).hasValue(3);
        assertThat(cacheStatus(after)).isEqualTo("shiguchi; fwd=miss; stored");
    }

    @Test
    @DisplayName("GIVEN a cached query WHEN a mutation on another service runs SHOULD keep it")
    void given_cached_query_when_mutation_on_other_service_runs_should_keep() {
        run(http(ITEM_QUERY, Map.of("id", "1")));

        run(http("mutation { ping }", null));
        WebGraphQlResponse after = run(http(ITEM_QUERY, Map.of("id", "1")));

        assertThat(executions).hasValue(2);
        assertThat(cacheStatus(after)).isEqualTo("shiguchi; hit");
    }

    @Test
    @DisplayName("GIVEN a cached query WHEN a mutation hides its root fields in a fragment SHOULD evict everything")
    void given_cached_query_when_mutation_root_is_fragment_should_evict_everything() {
        run(http(ITEM_QUERY, Map.of("id", "1")));

        run(http("mutation { ...M } fragment M on Mutation { ping }", null));

        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("GIVEN a response with errors WHEN the query is repeated SHOULD not have been cached")
    void given_error_response_when_repeated_should_not_cache() {
        WebGraphQlResponse first = run(failingChain, http(ITEM_QUERY, Map.of("id", "1")), "alice");
        run(failingChain, http(ITEM_QUERY, Map.of("id", "1")), "alice");

        assertThat(cacheStatus(first)).isEqualTo("shiguchi; fwd=miss");
        assertThat(executions).hasValue(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("GIVEN vary-by-principal WHEN two callers send the same query SHOULD each get their own entry")
    void given_vary_by_principal_when_two_callers_should_have_own_entries() {
        run(chain, http(ITEM_QUERY, Map.of("id", "1")), "alice");
        WebGraphQlResponse bob = run(chain, http(ITEM_QUERY, Map.of("id", "1")), "bob");
        WebGraphQlResponse anonymous = run(chain, http(ITEM_QUERY, Map.of("id", "1")), null);
        WebGraphQlResponse anonymousAgain = run(chain, http(ITEM_QUERY, Map.of("id", "1")), null);

        assertThat(executions).hasValue(3);
        assertThat(cacheStatus(bob)).startsWith("shiguchi; fwd=miss");
        assertThat(cacheStatus(anonymous)).startsWith("shiguchi; fwd=miss");
        assertThat(cacheStatus(anonymousAgain)).isEqualTo("shiguchi; hit");
    }

    @Test
    @DisplayName("GIVEN vary-by-principal off WHEN two callers send the same query SHOULD share one entry")
    void given_vary_by_principal_off_when_two_callers_should_share_entry() {
        config.setVaryByPrincipal(false);
        rebuild();

        run(chain, http(ITEM_QUERY, Map.of("id", "1")), "alice");
        WebGraphQlResponse bob = run(chain, http(ITEM_QUERY, Map.of("id", "1")), "bob");

        assertThat(executions).hasValue(1);
        assertThat(cacheStatus(bob)).isEqualTo("shiguchi; hit");
    }

    @Test
    @DisplayName("GIVEN vary-by-principal off WHEN the method carries auth_scopes SHOULD still key per caller")
    void given_vary_by_principal_off_when_method_has_auth_scopes_should_key_per_caller() {
        config.setVaryByPrincipal(false);
        when(registry.findByGraphQlName("item"))
                .thenReturn(List.of(RouteDefinition.builder(STORE, "GetItem", MethodDescriptor.MethodType.UNARY, "h:1")
                        .graphQl("item", GraphQlOperationType.QUERY)
                        .cacheTtlSeconds(30)
                        .authScopes(List.of("items:read"))
                        .build()));
        rebuild();

        run(chain, http(ITEM_QUERY, Map.of("id", "1")), "alice");
        WebGraphQlResponse bob = run(chain, http(ITEM_QUERY, Map.of("id", "1")), "bob");

        assertThat(executions).hasValue(2);
        assertThat(cacheStatus(bob)).startsWith("shiguchi; fwd=miss");
    }

    @Test
    @DisplayName("GIVEN a root field without cache_ttl_seconds WHEN queried twice SHOULD execute twice")
    void given_root_field_without_ttl_when_queried_twice_should_execute_twice() {
        run(http("{ listItems { id } }", null));
        WebGraphQlResponse second = run(http("{ listItems { id } }", null));

        assertThat(executions).hasValue(2);
        assertThat(cacheStatus(second)).isNull();
    }

    @Test
    @DisplayName("GIVEN a query mixing a cacheable and an uncacheable root field WHEN repeated SHOULD not cache")
    void given_mixed_root_fields_when_repeated_should_not_cache() {
        run(http("{ item(id: \"1\") { id } listItems { id } }", null));
        run(http("{ item(id: \"1\") { id } listItems { id } }", null));

        assertThat(executions).hasValue(2);
    }

    @Test
    @DisplayName("GIVEN an introspection query WHEN repeated SHOULD pass through untouched")
    void given_introspection_query_when_repeated_should_pass_through() {
        run(http("{ __typename }", null));
        WebGraphQlResponse second = run(http("{ __typename }", null));

        assertThat(executions).hasValue(2);
        assertThat(cacheStatus(second)).isNull();
    }

    @Test
    @DisplayName("GIVEN a request over WebSocket WHEN repeated SHOULD pass through untouched")
    void given_websocket_request_when_repeated_should_pass_through() {
        run(webSocket(ITEM_QUERY));
        WebGraphQlResponse second = run(webSocket(ITEM_QUERY));

        assertThat(executions).hasValue(2);
        assertThat(cacheStatus(second)).isNull();
    }

    @Test
    @DisplayName("GIVEN a document that does not parse WHEN intercepted SHOULD pass through")
    void given_unparseable_document_when_intercepted_should_pass_through() {
        run(http("{ item(", null));
        run(http("{ item(", null));

        assertThat(executions).hasValue(2);
    }

    @Test
    @DisplayName("GIVEN the same variables in another order WHEN queried SHOULD hit the same entry")
    void given_variables_in_other_order_when_queried_should_hit_same_entry() {
        String query = "query Q($id: String!, $lang: String) { item(id: $id, lang: $lang) { id } }";
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "1");
        a.put("lang", "en");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("lang", "en");
        b.put("id", "1");

        run(http(query, a));
        WebGraphQlResponse second = run(http(query, b));

        assertThat(executions).hasValue(1);
        assertThat(cacheStatus(second)).isEqualTo("shiguchi; hit");
        assertThat(GraphQlResponseCache.key(query, "Q", a, "alice"))
                .isEqualTo(GraphQlResponseCache.key(query, "Q", b, "alice"))
                .isNotEqualTo(GraphQlResponseCache.key(query, "Q", Map.of("id", "2", "lang", "en"), "alice"));
    }

    @Test
    @DisplayName("GIVEN a document with two operations WHEN the request names one SHOULD cache that one")
    void given_two_operations_when_request_names_one_should_cache_it() {
        String doc = "query A { item(id: \"1\") { id } } query B { listItems { id } }";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", doc);
        body.put("operationName", "A");
        WebGraphQlRequest named = new WebGraphQlRequest(
                URI.create("http://gw/graphql"),
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of(),
                body,
                "1",
                Locale.ENGLISH);

        run(named);
        WebGraphQlResponse second = run(named);

        assertThat(executions).hasValue(1);
        assertThat(cacheStatus(second)).isEqualTo("shiguchi; hit");
    }

    @Test
    @DisplayName("GIVEN cached entries WHEN the schema reloads SHOULD start empty")
    void given_cached_entries_when_schema_reloads_should_start_empty() {
        run(http(ITEM_QUERY, Map.of("id", "1")));
        assertThat(cache.size()).isEqualTo(1);

        cache.onSchemaReloaded();
        WebGraphQlResponse after = run(http(ITEM_QUERY, Map.of("id", "1")));

        assertThat(cache.size()).isEqualTo(1);
        assertThat(executions).hasValue(2);
        assertThat(cacheStatus(after)).isEqualTo("shiguchi; fwd=miss; stored");
    }

    @Test
    @DisplayName("GIVEN max-entries WHEN more distinct queries are cached SHOULD stay within the bound")
    void given_max_entries_when_more_queries_cached_should_stay_within_bound() {
        config.setMaxEntries(2);
        rebuild();

        for (int i = 0; i < 10; i++) {
            run(http(ITEM_QUERY, Map.of("id", Integer.toString(i))));
        }

        assertThat(cache.size()).isLessThanOrEqualTo(2);
    }
}
