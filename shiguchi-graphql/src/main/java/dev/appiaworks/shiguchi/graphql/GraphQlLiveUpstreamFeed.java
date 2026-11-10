package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.cursus.PushBroker;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * Re-runs the live queries of a method when an event arrives on one of its {@code invalidated_by}
 * Cursus topics. Every instance sees the event itself, so the result stays local (it is not relayed).
 * The topics are exact ({@code namespace:name}): the broker has no wildcard subscription, so a
 * pattern is skipped with a warning. The routes are read again every {@code reconcile} period, so
 * methods that appear or change later are picked up.
 */
class GraphQlLiveUpstreamFeed implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GraphQlLiveUpstreamFeed.class);

    private final GrpcRouteRegistry registry;
    private final PushBroker broker;
    private final GraphQlLiveInvalidations invalidations;
    private final Duration reconcile;
    private final Map<Topic, Disposable> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile Disposable ticker;

    GraphQlLiveUpstreamFeed(
            GrpcRouteRegistry registry,
            PushBroker broker,
            GraphQlLiveInvalidations invalidations,
            Duration reconcile) {
        this.registry = registry;
        this.broker = broker;
        this.invalidations = invalidations;
        this.reconcile = reconcile;
    }

    @Override
    public void afterPropertiesSet() {
        reconcile();
        ticker = Flux.interval(reconcile, reconcile).subscribe(i -> reconcile(), e -> {});
    }

    @Override
    public void destroy() {
        Disposable t = ticker;
        if (t != null) t.dispose();
        subscriptions.values().forEach(Disposable::dispose);
        subscriptions.clear();
    }

    /** The topics now wanted, each with the services it invalidates. */
    Map<Topic, Set<String>> wanted() {
        Map<Topic, Set<String>> wanted = new HashMap<>();
        for (RouteDefinition route : registry.allRoutes()) {
            if (route.getGraphQlName() == null) continue;
            for (String text : route.getGraphQlInvalidatedBy()) {
                Topic topic = exact(text);
                if (topic != null) {
                    wanted.computeIfAbsent(topic, t -> new LinkedHashSet<>()).add(route.getFullServiceName());
                }
            }
        }
        return wanted;
    }

    synchronized void reconcile() {
        Map<Topic, Set<String>> wanted = wanted();
        for (Topic topic : new HashSet<>(subscriptions.keySet())) {
            if (!wanted.containsKey(topic)) {
                Disposable d = subscriptions.remove(topic);
                if (d != null) d.dispose();
            }
        }
        for (Topic topic : wanted.keySet()) {
            subscriptions.computeIfAbsent(
                    topic,
                    t -> broker.subscribe(t)
                            .subscribe(
                                    event -> invalidations.receive(wanted().getOrDefault(t, Set.of()), "upstream"),
                                    e -> log.warn("GraphQL live feed on {} ended: {}", t, e.toString())));
        }
    }

    int subscribed() {
        return subscriptions.size();
    }

    private Topic exact(String text) {
        if (text.indexOf('*') >= 0 || text.indexOf('{') >= 0 || text.indexOf(':') < 0) {
            if (warned.add(text)) {
                log.warn("GraphQL invalidated_by '{}' ignored: it must be an exact topic, namespace:name", text);
            }
            return null;
        }
        try {
            return Topic.parse(text);
        } catch (IllegalArgumentException e) {
            if (warned.add(text)) log.warn("GraphQL invalidated_by '{}' ignored: {}", text, e.getMessage());
            return null;
        }
    }
}
