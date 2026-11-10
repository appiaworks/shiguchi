package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition.GraphQlOperationType;
import io.grpc.MethodDescriptor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphQlLiveDependenciesTest {

    private final GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
    private final GraphQlLiveDependencies dependencies = new GraphQlLiveDependencies(registry);

    private static RouteDefinition route(String field, boolean live) {
        return RouteDefinition.builder("pkg.Store", "Get", MethodDescriptor.MethodType.UNARY, "localhost:1")
                .graphQl(field, GraphQlOperationType.QUERY)
                .graphQlLive(live)
                .build();
    }

    @Test
    @DisplayName("GIVEN a live query on a field WHEN live is unset THEN the shape is live")
    void GIVEN_live_query_WHEN_field_allows_live_SHOULD_be_live() {
        when(registry.findByGraphQlName("item")).thenReturn(List.of(route("item", true)));

        var shape = dependencies.shape("query Q @live { item { id } }", null);

        assertThat(shape.live()).isTrue();
        assertThat(shape.services()).containsExactly("pkg.Store");
    }

    @Test
    @DisplayName("GIVEN a live query WHEN a root field has live false THEN the shape is not live")
    void GIVEN_live_query_WHEN_field_opts_out_SHOULD_not_be_live() {
        when(registry.findByGraphQlName("item")).thenReturn(List.of(route("item", true)));
        when(registry.findByGraphQlName("secret")).thenReturn(List.of(route("secret", false)));

        var shape = dependencies.shape("query Q @live { item { id } secret { id } }", null);

        assertThat(shape.live()).isFalse();
    }
}
