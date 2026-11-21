package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.GraphQLContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.registries.DispatchPredicate;
import org.dataloader.registries.ScheduledDataLoaderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.DefaultBatchLoaderRegistry;
import reactor.core.publisher.Flux;

@DisplayName("Subscription batch window")
class WindowedDataLoaderTest {

    private static DataLoader<String, String> loader(List<List<String>> calls) {
        return DataLoaderFactory.newDataLoader(keys -> {
            calls.add(new ArrayList<>(keys));
            return CompletableFuture.completedFuture(keys);
        });
    }

    @Test
    @DisplayName("GIVEN loads asked at separate times inside the window WHEN dispatch is called for each "
            + "SHOULD send them as one batch")
    void given_loadsInsideWindow_when_dispatchedSeparately_should_shareOneBatch() throws Exception {
        List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
        WindowedDataLoader<String, String> windowed = new WindowedDataLoader<>(loader(calls), Duration.ofMillis(200));

        CompletableFuture<String> a = windowed.load("a");
        windowed.dispatch();
        CompletableFuture<String> b = windowed.load("b");
        windowed.dispatch();

        assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo("a");
        assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo("b");
        assertThat(calls).containsExactly(List.of("a", "b"));
    }

    @Test
    @DisplayName("GIVEN a load asked after the window fired WHEN dispatch is called SHOULD send a batch of its own")
    void given_loadAfterWindow_when_dispatched_should_sendSecondBatch() throws Exception {
        List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
        WindowedDataLoader<String, String> windowed = new WindowedDataLoader<>(loader(calls), Duration.ofMillis(50));

        CompletableFuture<String> a = windowed.load("a");
        windowed.dispatch();
        a.get(5, TimeUnit.SECONDS);
        CompletableFuture<String> b = windowed.load("b");
        windowed.dispatch();
        b.get(5, TimeUnit.SECONDS);

        assertThat(calls).containsExactly(List.of("a"), List.of("b"));
    }

    private static DataLoader<String, String> registered(Duration window, boolean subscription) {
        DefaultBatchLoaderRegistry delegate = new DefaultBatchLoaderRegistry();
        delegate.forName("Customer").<Object, Object>registerBatchLoader((keys, env) -> Flux.fromIterable(keys));
        ScheduledDataLoaderRegistry registry = ScheduledDataLoaderRegistry.newScheduledRegistry()
                .dispatchPredicate(DispatchPredicate.DISPATCH_NEVER)
                .build();
        GraphQLContext context = GraphQLContext.newContext().build();
        if (subscription) context.put(GatewayBatchLoaderRegistry.SUBSCRIPTION_KEY, Boolean.TRUE);
        new GatewayBatchLoaderRegistry(delegate, window).registerDataLoaders(registry, context);
        return registry.getDataLoader("Customer");
    }

    @Test
    @DisplayName("GIVEN a window and a subscription WHEN loaders are registered SHOULD wrap them")
    void given_windowAndSubscription_when_registered_should_wrapLoader() {
        assertThat(registered(Duration.ofMillis(50), true)).isInstanceOf(WindowedDataLoader.class);
    }

    @Test
    @DisplayName("GIVEN a window but a query WHEN loaders are registered SHOULD leave them unwrapped")
    void given_windowButQuery_when_registered_should_notWrapLoader() {
        assertThat(registered(Duration.ofMillis(50), false)).isNotInstanceOf(WindowedDataLoader.class);
    }

    @Test
    @DisplayName("GIVEN no window and a subscription WHEN loaders are registered SHOULD leave them unwrapped")
    void given_noWindow_when_registered_should_notWrapLoader() {
        assertThat(registered(Duration.ZERO, true)).isNotInstanceOf(WindowedDataLoader.class);
    }

    @Test
    @DisplayName("GIVEN documents WHEN asked whether the operation is a subscription SHOULD pick the named one")
    void given_documents_when_asked_should_detectSubscriptions() {
        String doc = "query Q { a } subscription S { b }";
        assertThat(DataLoaderChainingInterceptor.isSubscription(doc, "S")).isTrue();
        assertThat(DataLoaderChainingInterceptor.isSubscription(doc, "Q")).isFalse();
        assertThat(DataLoaderChainingInterceptor.isSubscription(doc, null)).isFalse();
        assertThat(DataLoaderChainingInterceptor.isSubscription("subscription { b }", null))
                .isTrue();
        assertThat(DataLoaderChainingInterceptor.isSubscription("{ not valid", null))
                .isFalse();
    }
}
