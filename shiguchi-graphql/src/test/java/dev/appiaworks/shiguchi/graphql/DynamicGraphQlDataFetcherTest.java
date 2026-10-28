package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.rest.HttpUpstreamDispatcher;
import dev.appiaworks.shiguchi.rest.MergeDispatcher;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import graphql.GraphQLContext;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.MethodDescriptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

/**
 * The fetcher's choice of transport: a route flagged as an HTTP passthrough (REST_DOWNSTREAM) goes to
 * the {@link HttpUpstreamDispatcher}, a gRPC route to the {@link ReactiveGrpcDispatcher}, and both
 * apply the same response pluck. The end-to-end starter tests cover the real DGS wiring.
 */
@DisplayName("DynamicGraphQlDataFetcher transport selection")
class DynamicGraphQlDataFetcherTest {

    private GrpcRouteRegistry registry;
    private ReactiveGrpcDispatcher grpcDispatcher;
    private HttpUpstreamDispatcher httpDispatcher;
    private GatewayAuthorizationEnforcer authEnforcer;
    private ServiceEndpointContext ctx;

    @BeforeEach
    void setUp() {
        registry = mock(GrpcRouteRegistry.class);
        grpcDispatcher = mock(ReactiveGrpcDispatcher.class);
        httpDispatcher = mock(HttpUpstreamDispatcher.class);
        authEnforcer = mock(GatewayAuthorizationEnforcer.class);
        when(authEnforcer.enforce(any(), any(), any())).thenReturn(Mono.empty());
        // A REST_DOWNSTREAM context: no channel, no descriptors registered for the lookup.
        ctx = new ServiceEndpointContext(new ServiceDefinition(), null);
    }

    private DynamicGraphQlDataFetcher fetcher(HttpUpstreamDispatcher http) {
        GrpcMetadataPopulator populator = mock(GrpcMetadataPopulator.class);
        return new DynamicGraphQlDataFetcher(
                registry,
                grpcDispatcher,
                mock(RelayConnectionTransformer.class),
                mock(GraphQlFieldMaskInjector.class),
                mock(GraphQlSelectionProjector.class),
                populator,
                authEnforcer,
                mock(MergeDispatcher.class),
                http);
    }

    private static RouteDefinition.Builder route(String field, String transportTarget) {
        return RouteDefinition.builder(
                        "rest.inventory.Inventory", "GetItem", MethodDescriptor.MethodType.UNARY, transportTarget)
                .graphQl(field, RouteDefinition.GraphQlOperationType.QUERY);
    }

    private DataFetchingEnvironment env(String field, Map<String, Object> args) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getField()).thenReturn(Field.newField(field).build());
        when(env.getArguments()).thenReturn(args);
        // No ServerWebExchange in the context: the fetcher falls back to the security-context metadata.
        when(env.getGraphQlContext()).thenReturn(GraphQLContext.newContext().build());
        return env;
    }

    private void register(RouteDefinition r) {
        when(registry.findByGraphQlName(r.getGraphQlName())).thenReturn(List.of(r));
        when(registry.contextFor(r)).thenReturn(ctx);
    }

    @SuppressWarnings("unchecked")
    private static Object await(Object result) throws ExecutionException, InterruptedException {
        return ((CompletableFuture<Object>) result).get();
    }

    @Test
    @DisplayName("Given a route marked as an HTTP passthrough, when the field is fetched, "
            + "should dispatch through the HTTP dispatcher and never touch the gRPC one")
    void given_passthroughRoute_when_fetched_should_dispatchOverHttp() throws Exception {
        RouteDefinition r = route("getItem", "http://inventory:8081")
                .httpUpstream("http://inventory:8081")
                .build();
        register(r);
        Map<String, Object> args = Map.of("sku", "42");
        when(httpDispatcher.dispatchToMap(eq(r), eq(ctx), eq(args), any(), any()))
                .thenReturn(Mono.just(Map.of("sku", "42", "qty", 7L)));

        Object result = await(fetcher(httpDispatcher).get(env("getItem", args)));

        assertThat(result).isEqualTo(Map.of("sku", "42", "qty", 7L));
        verify(grpcDispatcher, never()).dispatchToMap(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Given the request's Reactor context saved in the GraphQL context, when the field is fetched, "
            + "should dispatch with the caller's principal, not anonymous")
    void given_savedRequestContext_when_fetched_should_dispatchAsTheCaller() throws Exception {
        RouteDefinition r = route("getItem", "localhost:9090").build();
        register(r);
        Map<String, Object> args = Map.of("sku", "42");
        var metadata = org.mockito.ArgumentCaptor.forClass(io.grpc.Metadata.class);
        when(grpcDispatcher.dispatchToMap(eq(r), eq(ctx), eq(args), metadata.capture(), any()))
                .thenReturn(Mono.just(Map.of("sku", "42")));
        DataFetchingEnvironment env = env("getItem", args);
        GraphQlRequestContext.save(
                env.getGraphQlContext(),
                ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken("bob", "n/a")));

        await(fetcher(httpDispatcher).get(env));

        assertThat(metadata.getValue()
                        .get(io.grpc.Metadata.Key.of("x-principal", io.grpc.Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("bob");
    }

    @Test
    @DisplayName("Given no saved request context, when the field is fetched, should dispatch as anonymous")
    void given_noSavedRequestContext_when_fetched_should_dispatchAsAnonymous() throws Exception {
        RouteDefinition r = route("getItem", "localhost:9090").build();
        register(r);
        Map<String, Object> args = Map.of("sku", "42");
        var metadata = org.mockito.ArgumentCaptor.forClass(io.grpc.Metadata.class);
        when(grpcDispatcher.dispatchToMap(eq(r), eq(ctx), eq(args), metadata.capture(), any()))
                .thenReturn(Mono.just(Map.of("sku", "42")));

        await(fetcher(httpDispatcher).get(env("getItem", args)));

        assertThat(metadata.getValue()
                        .get(io.grpc.Metadata.Key.of("x-principal", io.grpc.Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("anonymous");
    }

    @Test
    @DisplayName("Given a gRPC route, when the field is fetched, "
            + "should dispatch over the channel and never call the HTTP dispatcher")
    void given_grpcRoute_when_fetched_should_dispatchOverGrpc() throws Exception {
        RouteDefinition r = route("getItem", "localhost:9090").build();
        register(r);
        Map<String, Object> args = Map.of("sku", "42");
        when(grpcDispatcher.dispatchToMap(eq(r), eq(ctx), eq(args), any(), any()))
                .thenReturn(Mono.just(Map.of("sku", "42")));

        Object result = await(fetcher(httpDispatcher).get(env("getItem", args)));

        assertThat(result).isEqualTo(Map.of("sku", "42"));
        verify(httpDispatcher, never()).dispatchToMap(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Given a passthrough route with a response pluck, when the HTTP response wraps a list, "
            + "should return the plucked list directly")
    void given_passthroughRouteWithPluck_when_fetched_should_returnPluckedItems() throws Exception {
        RouteDefinition r = route("listItems", "http://inventory:8081")
                .httpUpstream("http://inventory:8081")
                .responsePluck("items")
                .build();
        register(r);
        when(httpDispatcher.dispatchToMap(eq(r), eq(ctx), any(), any(), any()))
                .thenReturn(Mono.just(Map.of("items", List.of(Map.of("sku", "1"), Map.of("sku", "2")))));

        Object result = await(fetcher(httpDispatcher).get(env("listItems", Map.of())));

        assertThat(result).isEqualTo(List.of(Map.of("sku", "1"), Map.of("sku", "2")));
    }

    @Test
    @DisplayName("Given a passthrough route but no HTTP dispatcher wired, when the field is fetched, "
            + "should fail with a message naming the field")
    void given_passthroughRouteWithoutHttpDispatcher_when_fetched_should_failClearly() {
        RouteDefinition r = route("getItem", "http://inventory:8081")
                .httpUpstream("http://inventory:8081")
                .build();
        register(r);

        Object result = fetcher(null).get(env("getItem", Map.of("sku", "42")));

        assertThatThrownBy(() -> await(result))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("getItem")
                .hasMessageContaining("HttpUpstreamDispatcher");
    }

    @Test
    @DisplayName("Given a passthrough route, when the scope check rejects the caller, "
            + "should propagate the rejection and never call the backend")
    void given_passthroughRoute_when_scopeCheckFails_should_notDispatch() {
        RouteDefinition r = route("getSecret", "http://inventory:8081")
                .httpUpstream("http://inventory:8081")
                .build();
        register(r);
        when(authEnforcer.enforce(any(), any(), any()))
                .thenReturn(Mono.error(new SecurityException("missing scope inventory.secret")));

        Object result = fetcher(httpDispatcher).get(env("getSecret", Map.of()));

        assertThatThrownBy(() -> await(result)).hasMessageContaining("inventory.secret");
        verify(httpDispatcher, never()).dispatchToMap(any(), any(), any(), any(), any());
    }
}
