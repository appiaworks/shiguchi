package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.cursus.PushBroker;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.Disposable;

/**
 * Carries live-query invalidations between gateway instances over the Cursus broker: one topic,
 * the invalidated services in an attribute, the sender's instance id as origin. Nothing is stored,
 * so a missed one is repaired by the live query's refresh interval.
 */
class GraphQlLiveCursusBridge implements InitializingBean, DisposableBean {

    static final Topic TOPIC = new Topic("shiguchi.graphql", "live.invalidate");
    static final String SERVICES = "services";
    static final String ORIGIN = "origin";

    private static final Logger log = LoggerFactory.getLogger(GraphQlLiveCursusBridge.class);

    private final GraphQlLiveInvalidations invalidations;
    private final PushBroker broker;
    private final String instanceId;
    private volatile Disposable subscription;

    GraphQlLiveCursusBridge(GraphQlLiveInvalidations invalidations, PushBroker broker, String instanceId) {
        this.invalidations = invalidations;
        this.broker = broker;
        this.instanceId = instanceId;
    }

    @Override
    public void afterPropertiesSet() {
        invalidations.relayTo(this::publish);
        subscription = broker.subscribe(TOPIC)
                .filter(event -> !instanceId.equals(event.attributes().get(ORIGIN)))
                .subscribe(this::receive, e -> log.warn("GraphQL live invalidation feed ended: {}", e.toString()));
        log.info("GraphQL live queries are invalidated across instances through the Cursus broker");
    }

    @Override
    public void destroy() {
        invalidations.relayTo(null);
        Disposable s = subscription;
        if (s != null) s.dispose();
    }

    private void publish(Set<String> services) {
        PushEvent event = new PushEvent(
                TOPIC,
                UUID.randomUUID().toString(),
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of(SERVICES, String.join(",", new TreeSet<>(services)), ORIGIN, instanceId),
                null,
                null);
        broker.publish(event).subscribe(null, e -> log.warn("GraphQL live invalidation not sent: {}", e.toString()));
    }

    private void receive(PushEvent event) {
        String services = event.attributes().get(SERVICES);
        if (services == null || services.isBlank()) return;
        invalidations.receive(Set.copyOf(Arrays.asList(services.split(","))));
    }
}
