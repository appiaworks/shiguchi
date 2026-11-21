package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import graphql.GraphQLContext;
import java.time.Duration;
import java.util.Set;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;
import org.dataloader.registries.DispatchPredicate;
import org.dataloader.registries.ScheduledDataLoaderRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.graphql.execution.DefaultBatchLoaderRegistry;
import org.springframework.stereotype.Component;

/**
 * Spring GraphQL's {@link BatchLoaderRegistry}, with a dispatch predicate on every loader it
 * registers.
 *
 * <p>DGS hands each request a {@link ScheduledDataLoaderRegistry} whose registry-level predicate
 * is {@code DISPATCH_NEVER}; the loaders DGS discovers itself are registered with
 * {@code DISPATCH_ALWAYS} one by one. Spring GraphQL then adds the loaders registered through
 * {@link BatchLoaderRegistry} (the gateway's {@code loader_for} and REST batch loaders) with plain
 * {@code register(name, loader)}, so they fall under the registry-level predicate and a
 * {@code dispatchAll()} from graphql-java's per-level strategy skips them: every relationship
 * field waits for a dispatch that never comes. graphql-java 25's chaining mode happens to dispatch
 * loaders directly and masks this; graphql-java 24 (the Boot 3 line) does not.
 *
 * <p>This registry re-registers what the default registry just added with
 * {@code DISPATCH_ALWAYS}, and only that: loaders DGS registered keep their own predicates. On a
 * plain {@link DataLoaderRegistry} nothing changes.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayBatchLoaderRegistry implements BatchLoaderRegistry {

    /** GraphQL context key {@link DataLoaderChainingInterceptor} sets on subscription operations. */
    static final String SUBSCRIPTION_KEY = "shiguchi.subscription";

    private final BatchLoaderRegistry delegate;
    private final Duration subscriptionWindow;

    public GatewayBatchLoaderRegistry() {
        this(new DefaultBatchLoaderRegistry());
    }

    @Autowired
    public GatewayBatchLoaderRegistry(GatewayProperties properties) {
        this(
                new DefaultBatchLoaderRegistry(),
                properties.getGraphql().getDataLoader().getSubscriptionBatchWindow());
    }

    GatewayBatchLoaderRegistry(BatchLoaderRegistry delegate) {
        this(delegate, Duration.ZERO);
    }

    GatewayBatchLoaderRegistry(BatchLoaderRegistry delegate, Duration subscriptionWindow) {
        this.delegate = delegate;
        this.subscriptionWindow = subscriptionWindow == null ? Duration.ZERO : subscriptionWindow;
    }

    @Override
    public <K, V> RegistrationSpec<K, V> forTypePair(Class<K> keyType, Class<V> valueType) {
        return delegate.forTypePair(keyType, valueType);
    }

    @Override
    public <K, V> RegistrationSpec<K, V> forName(String name) {
        return delegate.forName(name);
    }

    /**
     * Always {@code true}. Spring GraphQL asks once, on the first request, and caches the answer:
     * a {@code false} there would skip {@link #registerDataLoaders} for the life of the
     * application, so a {@code loader_for} loader registered later (a service that loaded after
     * startup) would never reach a request. With no loaders the call adds nothing.
     */
    @Override
    public boolean hasRegistrations() {
        return true;
    }

    @Override
    public void registerDataLoaders(DataLoaderRegistry registry, GraphQLContext context) {
        Set<String> before = Set.copyOf(registry.getKeys());
        delegate.registerDataLoaders(registry, context);
        if (registry instanceof ScheduledDataLoaderRegistry scheduled) {
            // A subscription's loaders dispatch one window after they are asked, so the loads of
            // events arriving inside it share a batch; anything else dispatches as soon as asked.
            boolean windowed = !subscriptionWindow.isZero()
                    && !subscriptionWindow.isNegative()
                    && context.get(SUBSCRIPTION_KEY) == Boolean.TRUE;
            for (String key : Set.copyOf(registry.getKeys())) {
                if (!before.contains(key)) {
                    DataLoader<Object, Object> loader = registry.getDataLoader(key);
                    scheduled.register(
                            key,
                            windowed ? new WindowedDataLoader<>(loader, subscriptionWindow) : loader,
                            DispatchPredicate.DISPATCH_ALWAYS);
                }
            }
        }
    }
}
