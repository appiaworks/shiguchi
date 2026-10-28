package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * BDD-style unit tests for {@link GrpcBatchLoader#loadMapped(List)}.
 *
 * <p>The dispatcher is mocked so tests run without a real gRPC channel.
 * All Reactor sequences are verified with {@link StepVerifier}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcBatchLoader")
class GrpcBatchLoaderTest {

    @Mock
    private ReactiveGrpcDispatcher dispatcher;

    @Mock
    private RouteDefinition route;

    @Mock
    private ServiceEndpointContext ctx;

    // ---------------------------------------------------------------------------
    // Shared setup helpers
    // ---------------------------------------------------------------------------

    private GrpcBatchLoader loaderFor(String batchKeyJsonName, String batchResultJsonName, String entityKeyJsonName) {
        return new GrpcBatchLoader(route, ctx, dispatcher, batchKeyJsonName, batchResultJsonName, entityKeyJsonName);
    }

    @BeforeEach
    void stubRouteName() {
        when(route.getGraphQlName()).thenReturn("getEntitiesByIds");
    }

    // ---------------------------------------------------------------------------
    // SERVER_STREAMING path
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Given a SERVER_STREAMING route, when loadMapped is called, should delegate to dispatchStreamingToMap")
    void given_serverStreamingRoute_when_loadMapped_should_delegateToDispatchStreamingToMap() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.SERVER_STREAMING);

        Map<String, Object> item = Map.of("id", "42", "name", "Tritanium");
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.just(item));

        GrpcBatchLoader loader = loaderFor("ids", "", "id");

        // Act & Assert
        StepVerifier.create(loader.loadMapped(List.of("42"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).containsKey("42");
                    assertThat(result.get("42")).isEqualTo(item);
                })
                .verifyComplete();

        verify(dispatcher).dispatchStreamingToMap(eq(route), eq(ctx), any(), any());
    }

    @Test
    @DisplayName(
            "Given a SERVER_STREAMING route returning multiple items, when loadMapped is called, should build map keyed by entityKeyJsonName")
    void given_serverStreamingRouteWithMultipleItems_when_loadMapped_should_buildMapKeyedByEntityKey() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.SERVER_STREAMING);

        Map<String, Object> item1 = Map.of("typeId", "34", "name", "Tritanium");
        Map<String, Object> item2 = Map.of("typeId", "35", "name", "Pyerite");
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.just(item1, item2));

        GrpcBatchLoader loader = loaderFor("ids", "", "typeId");

        // Act & Assert
        StepVerifier.create(loader.loadMapped(List.of("34", "35"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).hasSize(2);
                    assertThat(result.get("34")).isEqualTo(item1);
                    assertThat(result.get("35")).isEqualTo(item2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Given a SERVER_STREAMING route with duplicate entity keys, when loadMapped is called, should keep first item and discard subsequent duplicates")
    void given_serverStreamingRouteWithDuplicateKeys_when_loadMapped_should_keepFirstItemOnly() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.SERVER_STREAMING);

        Map<String, Object> first = Map.of("id", "99", "rev", "1");
        Map<String, Object> second = Map.of("id", "99", "rev", "2");
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.just(first, second));

        GrpcBatchLoader loader = loaderFor("ids", "", "id");

        // Act & Assert
        StepVerifier.create(loader.loadMapped(List.of("99"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).hasSize(1);
                    assertThat(result.get("99").get("rev")).isEqualTo("1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Given a SERVER_STREAMING route whose stream times out after 10 seconds, when loadMapped is called, should complete with TimeoutException")
    void given_serverStreamingRouteWithTimeoutDispatch_when_loadMapped_should_completeWithTimeoutException() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.SERVER_STREAMING);

        // Never emits — triggers the 10-second timeout
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.never());

        GrpcBatchLoader loader = loaderFor("ids", "", "id");

        // Act & Assert — use virtual time to avoid a 10-second real wait
        StepVerifier.withVirtualTime(() -> loader.loadMapped(List.of("1"), new Metadata()))
                .thenAwait(Duration.ofSeconds(11))
                .expectError()
                .verify();
    }

    @Test
    @DisplayName(
            "Given a SERVER_STREAMING route and an item whose entityKeyJsonName is absent, when loadMapped is called, should key the item as the string 'null'")
    void given_serverStreamingItemWithMissingEntityKey_when_loadMapped_should_keyItemAsStringNull() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.SERVER_STREAMING);

        // Item does not contain "id" — String.valueOf(null) = "null"
        Map<String, Object> item = Map.of("name", "Orphan");
        when(dispatcher.dispatchStreamingToMap(eq(route), eq(ctx), any(), any()))
                .thenReturn(Flux.just(item));

        GrpcBatchLoader loader = loaderFor("ids", "", "id");

        // Act & Assert
        StepVerifier.create(loader.loadMapped(List.of(), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).containsKey("null");
                    assertThat(result.get("null")).isEqualTo(item);
                })
                .verifyComplete();
    }

    // ---------------------------------------------------------------------------
    // UNARY path
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a UNARY route, when loadMapped is called, should delegate to dispatchToMap and extract the repeated result field")
    void given_unaryRoute_when_loadMapped_should_delegateToDispatchToMapAndExtractRepeatedField() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.UNARY);

        Map<String, Object> entity = Map.of("id", "7", "value", "42.5");
        Map<String, Object> response = Map.of("results", List.of(entity));
        when(dispatcher.dispatchToMap(eq(route), eq(ctx), any(), any())).thenReturn(Mono.just(response));

        GrpcBatchLoader loader = loaderFor("ids", "results", "id");

        // Act & Assert
        StepVerifier.create(loader.loadMapped(List.of("7"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).hasSize(1);
                    assertThat(result.get("7")).isEqualTo(entity);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Given a UNARY route whose dispatch times out after 10 seconds, when loadMapped is called, should complete with TimeoutException")
    void given_unaryRouteWithTimeoutDispatch_when_loadMapped_should_completeWithTimeoutException() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.UNARY);

        // Never resolves — triggers the 10-second timeout
        when(dispatcher.dispatchToMap(eq(route), eq(ctx), any(), any())).thenReturn(Mono.never());

        GrpcBatchLoader loader = loaderFor("ids", "results", "id");

        // Act & Assert — virtual time collapses the 10-second wait
        StepVerifier.withVirtualTime(() -> loader.loadMapped(List.of("1"), new Metadata()))
                .thenAwait(Duration.ofSeconds(11))
                .expectError(java.util.concurrent.TimeoutException.class)
                .verify();
    }

    @Test
    @DisplayName(
            "Given a UNARY route and an empty key list, when loadMapped is called, should return an empty map without error")
    void given_unaryRouteAndEmptyKeyList_when_loadMapped_should_returnEmptyMapWithoutError() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.UNARY);

        // Response contains no results — simulates a backend that echoes an empty list
        Map<String, Object> response = Map.of("results", List.of());
        when(dispatcher.dispatchToMap(eq(route), eq(ctx), any(), any())).thenReturn(Mono.just(response));

        GrpcBatchLoader loader = loaderFor("ids", "results", "id");

        // Act & Assert
        StepVerifier.create(loader.loadMapped(List.of(), new Metadata()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Given a UNARY route whose response omits the batchResultJsonName field, when loadMapped is called, should return an empty map")
    void given_unaryRouteWithMissingResultField_when_loadMapped_should_returnEmptyMap() {
        // Arrange
        when(route.getMethodType()).thenReturn(MethodDescriptor.MethodType.UNARY);

        // Response does not contain the expected "items" key → getOrDefault returns List.of()
        Map<String, Object> response = Map.of("unrelated", "value");
        when(dispatcher.dispatchToMap(eq(route), eq(ctx), any(), any())).thenReturn(Mono.just(response));

        GrpcBatchLoader loader = loaderFor("ids", "items", "id");

        // Act & Assert
        StepVerifier.create(loader.loadMapped(List.of("1", "2"), new Metadata()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }
}
