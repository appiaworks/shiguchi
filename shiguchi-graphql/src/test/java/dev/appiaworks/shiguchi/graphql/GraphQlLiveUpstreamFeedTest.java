package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.cursus.MemoryPushBroker;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition.GraphQlOperationType;
import io.grpc.MethodDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Live queries invalidated by upstream topics")
class GraphQlLiveUpstreamFeedTest {

    private static RouteDefinition route(List<String> topics) {
        return RouteDefinition.builder("shop.Orders", "List", MethodDescriptor.MethodType.UNARY, "localhost:1")
                .graphQl("orders", GraphQlOperationType.QUERY)
                .graphQlInvalidatedBy(topics)
                .build();
    }

    @Test
    @DisplayName("GIVEN a method with invalidated_by WHEN an event arrives on its topic SHOULD signal its service")
    void given_invalidatedBy_when_eventArrives_should_signalService() throws Exception {
        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        when(registry.allRoutes()).thenReturn(List.of(route(List.of("orders:changed", "orders:*", "bad"))));
        MemoryPushBroker broker = new MemoryPushBroker();
        GraphQlLiveInvalidations invalidations = new GraphQlLiveInvalidations();
        List<Set<String>> seen = new CopyOnWriteArrayList<>();
        invalidations.signals().subscribe(seen::add);
        GraphQlLiveUpstreamFeed feed =
                new GraphQlLiveUpstreamFeed(registry, broker, invalidations, Duration.ofHours(1));
        feed.afterPropertiesSet();

        assertThat(feed.subscribed()).isEqualTo(1);
        broker.publish(new PushEvent(new Topic("orders", "other"), "1", "{}".getBytes(), Map.of(), null, null))
                .block();
        broker.publish(new PushEvent(new Topic("orders", "changed"), "2", "{}".getBytes(), Map.of(), null, null))
                .block();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (seen.isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(seen).containsExactly(Set.of("shop.Orders"));
        feed.destroy();
    }
}
