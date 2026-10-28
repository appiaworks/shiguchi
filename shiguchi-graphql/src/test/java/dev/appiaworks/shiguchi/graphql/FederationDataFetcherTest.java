package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Metadata;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * BDD-style unit tests for {@link FederationDataFetcher}.
 *
 * <p>Tests cover:
 * - fetchService() returning SDL for Apollo Federation _service field
 * - fetchEntities() resolving federation representations to entity objects
 * - Batch loader dispatch and result assembly for single/multiple types
 * - Edge cases: null/empty representations, missing keys, loader exceptions
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FederationDataFetcher")
class FederationDataFetcherTest {

    @Mock
    private GraphQlSchemaBuilder schemaBuilder;

    @Mock
    private GrpcBatchDataLoaderRegistrar loaderRegistrar;

    @Mock
    private DataFetchingEnvironment env;

    @Mock
    private GraphQLContext graphQlContext;

    @Mock
    private ServerWebExchange serverWebExchange;

    @Mock
    private GrpcBatchLoader loaderForBook;

    @Mock
    private GrpcBatchLoader loaderForPublisher;

    private FederationDataFetcher fetcher;

    // ---------------------------------------------------------------------------
    // Shared setup
    // ---------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        fetcher = new FederationDataFetcher(schemaBuilder, loaderRegistrar);
    }

    // ---------------------------------------------------------------------------
    // fetchService() tests
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Given fetchService called, should return map with 'sdl' key containing generated schema SDL")
    void given_generatedSchema_when_fetchServiceCalled_should_returnMapWithSdlKey() {
        // Arrange
        String expectedSdl = "type Query { hello: String }";
        when(schemaBuilder.getGeneratedSdl()).thenReturn(expectedSdl);

        // Act
        Object result = fetcher.fetchService(env);

        // Assert
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> serviceMap = (Map<String, String>) result;
        assertThat(serviceMap).containsEntry("sdl", expectedSdl);
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with null/empty representations
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given representations is null, when fetchEntities called, should return completed future with empty list")
    void given_nullRepresentations_when_fetchEntities_should_returnEmptyList() throws Exception {
        // Arrange
        when(env.getArgument("representations")).thenReturn(null);

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given representations is empty list, when fetchEntities called, should return completed future with empty list")
    void given_emptyRepresentations_when_fetchEntities_should_returnEmptyList() throws Exception {
        // Arrange
        when(env.getArgument("representations")).thenReturn(List.of());

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with missing __typename or unknown types
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("Given representation missing __typename, when fetchEntities called, should return null at that index")
    void given_representationMissingTypeName_when_fetchEntities_should_returnNullAtIndex() throws Exception {
        // Arrange
        Map<String, Object> repWithoutTypeName = Map.of("id", "123");
        when(env.getArgument("representations")).thenReturn(List.of(repWithoutTypeName));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id"));

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).isNull();
    }

    @Test
    @DisplayName(
            "Given representation with unknown __typename, when fetchEntities called, should return null at that index")
    void given_unknownTypeName_when_fetchEntities_should_returnNullAtIndex() throws Exception {
        // Arrange
        Map<String, Object> rep = Map.of("__typename", "UnknownType", "id", "456");
        when(env.getArgument("representations")).thenReturn(List.of(rep));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id"));

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).isNull();
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with valid single representation
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given valid representation for known type, when fetchEntities called, should load via batch loader and return entity at correct index")
    void given_validRepresentationForKnownType_when_fetchEntities_should_loadAndReturnEntity() throws Exception {
        // Arrange
        Map<String, Object> rep = Map.of("__typename", "Book", "id", "34");
        Map<String, Object> entity = Map.of("id", "34", "name", "Tritanium");

        when(env.getArgument("representations")).thenReturn(List.of(rep));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id"));
        when(loaderRegistrar.getLoaders()).thenReturn(Map.of("Book", loaderForBook));
        when(loaderForBook.loadMapped(eq(List.of("34")), any(Metadata.class)))
                .thenReturn(Mono.just(Map.of("34", entity)));

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).isEqualTo(entity);
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with multiple representations for same type
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given two representations for same type, when fetchEntities called, should batch both IDs in one loader call and return both in order")
    void given_twoRepresentationsSameType_when_fetchEntities_should_batchBothAndReturnInOrder() throws Exception {
        // Arrange
        Map<String, Object> rep1 = Map.of("__typename", "Book", "id", "34");
        Map<String, Object> rep2 = Map.of("__typename", "Book", "id", "35");
        Map<String, Object> entity1 = Map.of("id", "34", "name", "Tritanium");
        Map<String, Object> entity2 = Map.of("id", "35", "name", "Pyerite");

        when(env.getArgument("representations")).thenReturn(List.of(rep1, rep2));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id"));
        when(loaderRegistrar.getLoaders()).thenReturn(Map.of("Book", loaderForBook));
        when(loaderForBook.loadMapped(eq(List.of("34", "35")), any(Metadata.class)))
                .thenReturn(Mono.just(Map.of("34", entity1, "35", entity2)));

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0)).isEqualTo(entity1);
        assertThat(result.get().get(1)).isEqualTo(entity2);
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with multiple representations for different types
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given two representations for different types, when fetchEntities called, should dispatch to separate loaders and reassemble in order")
    void given_twoRepresentationsDifferentTypes_when_fetchEntities_should_dispatchSeparateLoadersAndReassemble()
            throws Exception {
        // Arrange
        Map<String, Object> rep1 = Map.of("__typename", "Book", "id", "34");
        Map<String, Object> rep2 = Map.of("__typename", "Publisher", "publisherId", "30000142");
        Map<String, Object> entity1 = Map.of("id", "34", "name", "Tritanium");
        Map<String, Object> entity2 = Map.of("publisherId", "30000142", "name", "Penguin Press");

        when(env.getArgument("representations")).thenReturn(List.of(rep1, rep2));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id", "Publisher", "publisherId"));
        when(loaderRegistrar.getLoaders()).thenReturn(Map.of("Book", loaderForBook, "Publisher", loaderForPublisher));
        when(loaderForBook.loadMapped(eq(List.of("34")), any(Metadata.class)))
                .thenReturn(Mono.just(Map.of("34", entity1)));
        when(loaderForPublisher.loadMapped(eq(List.of("30000142")), any(Metadata.class)))
                .thenReturn(Mono.just(Map.of("30000142", entity2)));

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(2);
        assertThat(result.get().get(0)).isEqualTo(entity1);
        assertThat(result.get().get(1)).isEqualTo(entity2);
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with batch loader returning no entry for key
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given batch loader returns entity not found (key absent from map), when fetchEntities called, should return null at that index")
    void given_batchLoaderKeyNotFound_when_fetchEntities_should_returnNullAtIndex() throws Exception {
        // Arrange
        Map<String, Object> rep = Map.of("__typename", "Book", "id", "999");

        when(env.getArgument("representations")).thenReturn(List.of(rep));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id"));
        when(loaderRegistrar.getLoaders()).thenReturn(Map.of("Book", loaderForBook));
        // Loader returns an empty map (key not found)
        when(loaderForBook.loadMapped(eq(List.of("999")), any(Metadata.class))).thenReturn(Mono.just(Map.of()));

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).isNull();
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with batch loader exception
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given batch loader throws exception, when fetchEntities called, should return null at that index (exception suppressed)")
    void given_batchLoaderThrowsException_when_fetchEntities_should_returnNullAndSuppressException() throws Exception {
        // Arrange
        Map<String, Object> rep = Map.of("__typename", "Book", "id", "34");

        when(env.getArgument("representations")).thenReturn(List.of(rep));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id"));
        when(loaderRegistrar.getLoaders()).thenReturn(Map.of("Book", loaderForBook));
        when(loaderForBook.loadMapped(eq(List.of("34")), any(Metadata.class)))
                .thenReturn(Mono.error(new RuntimeException("Backend failure")));

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).isNull();
    }

    // ---------------------------------------------------------------------------
    // fetchEntities() with no loader registered for type
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given representation with type that has no registered loader, when fetchEntities called, should return null at that index")
    void given_typeWithNoRegisteredLoader_when_fetchEntities_should_returnNullAtIndex() throws Exception {
        // Arrange
        Map<String, Object> rep = Map.of("__typename", "Book", "id", "34");

        when(env.getArgument("representations")).thenReturn(List.of(rep));
        when(schemaBuilder.getFederationKeys()).thenReturn(Map.of("Book", "id"));
        // No loaders registered for Book
        when(loaderRegistrar.getLoaders()).thenReturn(Map.of());

        // Act
        CompletableFuture<List<Object>> result = fetcher.fetchEntities(env);

        // Assert
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0)).isNull();
    }
}
