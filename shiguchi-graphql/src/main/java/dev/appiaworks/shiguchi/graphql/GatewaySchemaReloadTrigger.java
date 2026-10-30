package dev.appiaworks.shiguchi.graphql;

import com.netflix.graphql.dgs.ReloadSchemaIndicator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Makes DGS rebuild its executable schema after the gateway's schema changed.
 *
 * <p>DGS builds the schema once and rebuilds it only when its {@link ReloadSchemaIndicator} says
 * so; the default one answers {@code dgs.reload}, off outside development. A service that loaded
 * after startup (a deferred backend coming up) or a refresh that swapped a schema regenerated the
 * SDL, but the schema serving requests kept the old fields, data fetchers and type resolvers.
 *
 * <p>{@link GatewaySchemaProvider#reload()} calls {@link #requestReload()}; the next request then
 * rebuilds the schema once, re-running every {@code @DgsTypeDefinitionRegistry} and
 * {@code @DgsCodeRegistry} method. The indicator bean, DGS's or the application's own, is wrapped
 * rather than replaced, so its own answer still counts.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewaySchemaReloadTrigger implements BeanPostProcessor {

    private final AtomicBoolean reloadRequested = new AtomicBoolean();

    /** The next request rebuilds the DGS schema. */
    public void requestReload() {
        reloadRequested.set(true);
    }

    /** Whether a rebuild was requested since the last call; clears the request. */
    boolean takeReloadRequest() {
        return reloadRequested.getAndSet(false);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ReloadSchemaIndicator indicator) {
            return (ReloadSchemaIndicator) () -> {
                boolean requested = takeReloadRequest();
                return indicator.reloadSchema() || requested;
            };
        }
        return bean;
    }
}
