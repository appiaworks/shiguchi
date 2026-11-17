package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.DataLoaderConfig;
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

    private static CompletableFuture<Object> failingLoad(
            DataFetchingEnvironment env, DataLoader<String, Object> loader, Throwable failure) {
        CompletableFuture<Object> failing = new CompletableFuture<>();
        failing.completeExceptionally(failure);
        when(loader.load(eq("42"))).thenReturn(failing);
        when(env.<String, Object>getDataLoader("Author")).thenReturn(loader);
        return failing;
    }

    @Test
    @DisplayName(
            "Given on-error ERROR and a failing DataLoader future, when get called, should fail the field so the error handler reports it")
    void given_onErrorError_and_loaderFutureFails_when_get_should_failTheField() {
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));
        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        failingLoad(env, loader, new RuntimeException("backend unreachable"));

        CompletableFuture<Object> result = fetcher.get(env);

        assertThatThrownBy(result::get)
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("backend unreachable");
    }

    @Test
    @DisplayName(
            "Given on-error ERROR and a TimeoutException from the DataLoader, when get called, should fail the field with the timeout as cause")
    void given_onErrorError_and_loaderFutureTimesOut_when_get_should_failTheFieldWithTimeout() {
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));
        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        failingLoad(env, loader, new java.util.concurrent.TimeoutException("simulated timeout"));

        CompletableFuture<Object> result = fetcher.get(env);

        assertThatThrownBy(result::get).hasRootCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    @DisplayName(
            "Given on-error NULL and a failing DataLoader future, when get called, should complete with null and no error")
    void given_onErrorNull_and_loaderFutureFails_when_get_should_completeWithNull() throws Exception {
        GrpcRelationshipDataFetcher fetcher =
                new GrpcRelationshipDataFetcher("authorId", "Author", DataLoaderConfig.OnError.NULL);
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));
        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        failingLoad(env, loader, new RuntimeException("backend unreachable"));

        CompletableFuture<Object> result = fetcher.get(env);

        assertThat(result.get()).isNull();
    }

    @Test
    @DisplayName("Given a key over the error cap, when get called, should resolve the suppressed marker to null")
    void given_suppressedMarker_when_get_should_completeWithNull() throws Exception {
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));
        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        when(loader.load(eq("42"))).thenReturn(CompletableFuture.completedFuture(BatchFailureSummary.SUPPRESSED));
        when(env.<String, Object>getDataLoader("Author")).thenReturn(loader);

        assertThat(fetcher.get(env).get()).isNull();
    }

    @Test
    @DisplayName(
            "Given no DataLoader registered for the type, when get called, should complete with null in ERROR mode")
    void given_noDataLoaderRegistered_when_get_should_completeWithNull() throws Exception {
        GrpcRelationshipDataFetcher fetcher = new GrpcRelationshipDataFetcher("authorId", "Author");
        DataFetchingEnvironment env = envWithSource(Map.of("authorId", "42"));
        when(env.<String, Object>getDataLoader("Author")).thenReturn(null);

        assertThat(fetcher.get(env).get()).isNull();
    }

    private static GrpcRelationshipDataFetcher manyFetcher(DataLoaderConfig.OnError onError) {
        return new GrpcRelationshipDataFetcher("id", "Book#by:authorId", onError, true);
    }

    @Test
    @DisplayName("GIVEN a one-to-many field and a source without the id WHEN fetched SHOULD return an empty list")
    void GIVEN_manyField_WHEN_idMissing_SHOULD_returnEmptyList() throws Exception {
        assertThat(manyFetcher(DataLoaderConfig.OnError.ERROR)
                        .get(envWithSource(Map.of()))
                        .get())
                .isEqualTo(java.util.List.of());
    }

    @Test
    @DisplayName("GIVEN a one-to-many field and a parent without children WHEN fetched SHOULD return [] not null")
    void GIVEN_manyField_WHEN_loaderYieldsNull_SHOULD_returnEmptyList() throws Exception {
        DataFetchingEnvironment env = envWithSource(Map.of("id", "42"));
        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        when(loader.load(eq("42"))).thenReturn(CompletableFuture.completedFuture(null));
        when(env.<String, Object>getDataLoader("Book#by:authorId")).thenReturn(loader);

        assertThat(manyFetcher(DataLoaderConfig.OnError.ERROR).get(env).get()).isEqualTo(java.util.List.of());
    }

    @Test
    @DisplayName("GIVEN a one-to-many field and on-error ERROR WHEN the batch failed SHOULD error the field")
    void GIVEN_manyFieldErrorMode_WHEN_batchFails_SHOULD_errorTheField() {
        DataFetchingEnvironment env = envWithSource(Map.of("id", "42"));
        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        CompletableFuture<Object> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("backend down"));
        when(loader.load(eq("42"))).thenReturn(failing);
        when(env.<String, Object>getDataLoader("Book#by:authorId")).thenReturn(loader);

        assertThatThrownBy(() ->
                        manyFetcher(DataLoaderConfig.OnError.ERROR).get(env).get())
                .hasMessageContaining("backend down");
    }

    @Test
    @DisplayName("GIVEN a one-to-many field and on-error NULL WHEN the batch failed SHOULD return []")
    void GIVEN_manyFieldNullMode_WHEN_batchFails_SHOULD_returnEmptyList() throws Exception {
        DataFetchingEnvironment env = envWithSource(Map.of("id", "42"));
        @SuppressWarnings("unchecked")
        DataLoader<String, Object> loader = mock(DataLoader.class);
        CompletableFuture<Object> failing = new CompletableFuture<>();
        failing.completeExceptionally(new RuntimeException("backend down"));
        when(loader.load(eq("42"))).thenReturn(failing);
        when(env.<String, Object>getDataLoader("Book#by:authorId")).thenReturn(loader);

        assertThat(manyFetcher(DataLoaderConfig.OnError.NULL).get(env).get()).isEqualTo(java.util.List.of());
    }
}
