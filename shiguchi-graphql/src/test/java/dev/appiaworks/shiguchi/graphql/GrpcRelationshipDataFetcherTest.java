package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.dataloader.DataLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for {@link GrpcRelationshipDataFetcher}.
 *
 * <p>{@link DataFetchingEnvironment} and {@link DataLoader} are mocked since only their
 * narrow contract used by the fetcher (getSource/getDataLoader, load) matters here — no
 * production code is changed.
 */
@DisplayName("GrpcRelationshipDataFetcher")
class GrpcRelationshipDataFetcherTest {

    private static DataFetchingEnvironment envWithSource(Object source) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSource()).thenReturn(source);
        return env;
    }

    @Test
    @DisplayName("Given source is not a Map, when get called, should return completed future with null")
    void given_sourceNotAMap_when_get_should_returnCompletedFutureWithNull() throws Exception {
        // Arrange
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource("not-a-map");

        // Act
        CompletableFuture<Object> result = fetcher.get(env);

        // Assert
        assertThat(result.get()).isNull();
    }

    @Test
    @DisplayName(
            "Given the parent Map has no value for the id field, when get called, should return completed future with null")
    void given_idFieldAbsentFromSourceMap_when_get_should_returnCompletedFutureWithNull() throws Exception {
        // Arrange
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("otherField", "value"));

        // Act
        CompletableFuture<Object> result = fetcher.get(env);

        // Assert
        assertThat(result.get()).isNull();
    }

    @Test
    @DisplayName(
            "Given no DataLoader registered for the resolved type, when get called, should return completed future with null")
    void given_noDataLoaderRegisteredForType_when_get_should_returnCompletedFutureWithNull() throws Exception {
        // Arrange
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));
        when(env.<String, Object>getDataLoader("Author")).thenReturn(null);

        // Act
        CompletableFuture<Object> result = fetcher.get(env);

        // Assert
        assertThat(result.get()).isNull();
    }

    @Test
    @DisplayName(
            "Given id present and DataLoader registered, when get called, should schedule a load with the stringified id and return its result")
    void given_idPresentAndLoaderRegistered_when_get_should_scheduleLoadAndReturnResult() throws Exception {
        // Arrange
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", 42L));

        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        Map<String, Object> authorEntity = Map.of("id", "42", "name", "Ursula K. Le Guin");
        when(loader.load(eq("42"))).thenReturn(CompletableFuture.completedFuture(authorEntity));
        when(env.<String, Object>getDataLoader("Author")).thenReturn(loader);

        // Act
        CompletableFuture<Object> result = fetcher.get(env);

        // Assert
        assertThat(result.get()).isEqualTo(authorEntity);
    }

    @Test
    @DisplayName(
            "Given the DataLoader future fails with a non-timeout exception, when get called, should complete with null instead of propagating the failure")
    void given_loaderFutureFailsWithNonTimeoutException_when_get_should_completeWithNull() throws Exception {
        // Arrange
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));

        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        CompletableFuture<Object> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("backend unreachable"));
        when(loader.load(eq("42"))).thenReturn(failing);
        when(env.<String, Object>getDataLoader("Author")).thenReturn(loader);

        // Act
        CompletableFuture<Object> result = fetcher.get(env);

        // Assert
        assertThat(result.get()).isNull();
    }

    @Test
    @DisplayName(
            "Given the DataLoader future fails with a TimeoutException, when get called, should complete with null (the timeout branch of exceptionally)")
    void given_loaderFutureFailsWithTimeoutException_when_get_should_completeWithNull() throws Exception {
        // Arrange — simulates what the fetcher's own orTimeout(10s) would produce, without
        // actually waiting out the real timeout window in this test.
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));

        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        CompletableFuture<Object> timedOut = new CompletableFuture<>();
        timedOut.completeExceptionally(new java.util.concurrent.TimeoutException("simulated timeout"));
        when(loader.load(eq("42"))).thenReturn(timedOut);
        when(env.<String, Object>getDataLoader("Author")).thenReturn(loader);

        // Act
        CompletableFuture<Object> result = fetcher.get(env);

        // Assert
        assertThat(result.get()).isNull();
    }
}
