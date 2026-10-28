package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.GraphQLContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.dataloader.registries.DispatchPredicate;
import org.dataloader.registries.ScheduledDataLoaderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.DefaultBatchLoaderRegistry;
import reactor.core.publisher.Mono;

/**
 * BDD-style unit tests for {@link GatewayBatchLoaderRegistry}.
 *
 * <p>Tests verify that the registry re-registers loaders added by the delegate with
 * {@code DISPATCH_ALWAYS} when the target registry is a {@link ScheduledDataLoaderRegistry},
 * overriding the registry-level {@code DISPATCH_NEVER} predicate. On a plain {@link
 * DataLoaderRegistry}, behavior is unchanged.
 */
class GatewayBatchLoaderRegistryTest {

    /** Fake batch loader that records the keys it received. */
    @SuppressWarnings("unchecked")
    private static class RecordingBatchLoader {
        Set<String> lastKeys;
        final Map<String, String> result = new HashMap<>();
    }

    @Test
    @DisplayName("Given a loader registered through forName with ScheduledDataLoaderRegistry DISPATCH_NEVER, "
            + "when registerDataLoaders runs and the loader is loaded and dispatched, "
            + "should complete the load with mapped value and batch function should receive the key")
    void given_loaderInScheduledRegistryDispatchNever_when_registerDataLoadersAndDispatch_should_completeLoadWithValue()
            throws Exception {
        // Arrange
        RecordingBatchLoader batchLoader = new RecordingBatchLoader();
        batchLoader.result.put("c1", "customer-1");

        DefaultBatchLoaderRegistry defaultRegistry = new DefaultBatchLoaderRegistry();
        defaultRegistry.forName("Customer").registerMappedBatchLoader((keys, env) -> {
            batchLoader.lastKeys = (Set<String>) (Set<?>) keys;
            return Mono.just((Map<Object, Object>) (Map<?, ?>) batchLoader.result);
        });

        ScheduledDataLoaderRegistry scheduledRegistry = ScheduledDataLoaderRegistry.newScheduledRegistry()
                .dispatchPredicate(DispatchPredicate.DISPATCH_NEVER)
                .build();

        GatewayBatchLoaderRegistry gwRegistry = new GatewayBatchLoaderRegistry(defaultRegistry);

        // Act
        gwRegistry.registerDataLoaders(
                scheduledRegistry, GraphQLContext.newContext().build());

        DataLoader<String, String> loader = scheduledRegistry.getDataLoader("Customer");
        CompletableFuture<String> future = loader.load("c1");
        scheduledRegistry.dispatchAll();

        // Assert
        String result = future.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("customer-1");
        assertThat(batchLoader.lastKeys).contains("c1");
    }

    @Test
    @DisplayName("Given ScheduledDataLoaderRegistry has existing loader with DISPATCH_NEVER, "
            + "when registerDataLoaders adds a new loader and dispatchAll is called, "
            + "should leave existing loader's future incomplete")
    void given_existingLoaderWithDispatchNever_when_registerDataLoaders_should_notTouchExistingLoader()
            throws Exception {
        // Arrange
        ScheduledDataLoaderRegistry scheduledRegistry = ScheduledDataLoaderRegistry.newScheduledRegistry()
                .dispatchPredicate(DispatchPredicate.DISPATCH_NEVER)
                .build();

        // Register "Theirs" with DISPATCH_NEVER explicitly
        scheduledRegistry.register(
                "Theirs",
                DataLoaderFactory.newDataLoader(keys -> CompletableFuture.completedFuture(keys)),
                DispatchPredicate.DISPATCH_NEVER);

        DefaultBatchLoaderRegistry defaultRegistry = new DefaultBatchLoaderRegistry();
        RecordingBatchLoader batchLoader = new RecordingBatchLoader();
        batchLoader.result.put("x", "value");
        defaultRegistry.forName("Customer").registerMappedBatchLoader((keys, env) -> {
            batchLoader.lastKeys = (Set<String>) (Set<?>) keys;
            return Mono.just((Map<Object, Object>) (Map<?, ?>) batchLoader.result);
        });

        GatewayBatchLoaderRegistry gwRegistry = new GatewayBatchLoaderRegistry(defaultRegistry);

        // Act
        gwRegistry.registerDataLoaders(
                scheduledRegistry, GraphQLContext.newContext().build());

        DataLoader<Object, Object> theirsLoader = scheduledRegistry.getDataLoader("Theirs");
        CompletableFuture<Object> future = theirsLoader.load("x");
        scheduledRegistry.dispatchAll();

        // Assert: the existing loader's future should not be completed by dispatchAll
        assertThat(future.isDone()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a plain DataLoaderRegistry, "
            + "WHEN registerDataLoaders runs and a load is dispatched, "
            + "SHOULD complete the load as before")
    void given_plainRegistry_when_registerDataLoaders_should_workUnchanged() throws Exception {
        // Arrange
        DataLoaderRegistry plainRegistry = new DataLoaderRegistry();

        RecordingBatchLoader batchLoader = new RecordingBatchLoader();
        batchLoader.result.put("c1", "customer-1");

        DefaultBatchLoaderRegistry defaultRegistry = new DefaultBatchLoaderRegistry();
        defaultRegistry.forName("Customer").registerMappedBatchLoader((keys, env) -> {
            batchLoader.lastKeys = (Set<String>) (Set<?>) keys;
            return Mono.just((Map<Object, Object>) (Map<?, ?>) batchLoader.result);
        });

        GatewayBatchLoaderRegistry gwRegistry = new GatewayBatchLoaderRegistry(defaultRegistry);

        // Act
        gwRegistry.registerDataLoaders(
                plainRegistry, GraphQLContext.newContext().build());

        DataLoader<String, String> loader = plainRegistry.getDataLoader("Customer");
        CompletableFuture<String> future = loader.load("c1");
        plainRegistry.dispatchAll();

        // Assert
        String result = future.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("customer-1");
    }
}
