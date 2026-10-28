package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.MergeRouteConfig;
import dev.appiaworks.shiguchi.config.MergeSourceConfig;
import dev.appiaworks.shiguchi.merge.MergeSource;
import dev.appiaworks.shiguchi.merge.MergeSpec;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.MethodDescriptor;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for how a merge is declared: the precedence between the three surfaces, and the
 * validation that keeps a malformed merge from reaching a caller.
 */
class MergeSpecResolverTest {

    private static RouteDefinition route(String path) {
        return RouteDefinition.builder("orders.OrderService", "list", MethodDescriptor.MethodType.UNARY, "localhost:1")
                .httpBinding("GET", path)
                .build();
    }

    private static GatewayProperties propertiesWithRoute(String path, String... services) {
        var props = new GatewayProperties();
        var cfg = new MergeRouteConfig();
        cfg.setPath(path);
        cfg.setMethod("GET");
        cfg.setTieBreakField("id");
        for (String service : services) {
            var source = new MergeSourceConfig();
            source.setService(service);
            source.setRpc("list");
            cfg.getSources().add(source);
        }
        props.getMerge().getRoutes().add(cfg);
        return props;
    }

    @Test
    @DisplayName("GIVEN no declaration anywhere WHEN resolving SHOULD leave the route an ordinary one")
    void given_noDeclaration_when_resolving_should_returnEmpty() {
        var resolver = new MergeSpecResolver(new GatewayProperties(), List.of(), new GrpcRouteRegistry());

        assertThat(resolver.resolve(route("/v1/orders"))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a YAML declaration for the path WHEN resolving SHOULD build a spec from it")
    void given_yamlDeclaration_when_resolving_should_buildSpec() {
        var resolver = new MergeSpecResolver(
                propertiesWithRoute("/v1/orders", "a.A", "b.B"), List.of(), new GrpcRouteRegistry());

        var spec = resolver.resolve(route("/v1/orders"));

        assertThat(spec).isPresent();
        assertThat(spec.get().sources()).hasSize(2);
        assertThat(spec.get().tieBreakField()).isEqualTo("id");
    }

    @Test
    @DisplayName("GIVEN YAML for a different path WHEN resolving SHOULD not apply it")
    void given_yamlForOtherPath_when_resolving_should_returnEmpty() {
        var resolver = new MergeSpecResolver(
                propertiesWithRoute("/v1/other", "a.A", "b.B"), List.of(), new GrpcRouteRegistry());

        assertThat(resolver.resolve(route("/v1/orders"))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN both an SPI bean and YAML WHEN resolving SHOULD prefer the SPI bean")
    void given_spiAndYaml_when_resolving_should_preferSpi() {
        MergeSpec fromSpi = new MergeSpec(
                List.of(MergeSource.presorted("spi.A", "list"), MergeSource.presorted("spi.B", "list")),
                "id",
                null,
                null,
                0);
        MergeSourceProvider provider = r -> Optional.of(fromSpi);

        var resolver = new MergeSpecResolver(
                propertiesWithRoute("/v1/orders", "yaml.A", "yaml.B"), List.of(provider), new GrpcRouteRegistry());

        var spec = resolver.resolve(route("/v1/orders"));

        assertThat(spec).isPresent();
        assertThat(spec.get().sources().get(0).fullServiceName())
                .as("a runtime-computed topology must win over a static one")
                .isEqualTo("spi.A");
    }

    @Test
    @DisplayName("GIVEN an SPI bean that declines WHEN resolving SHOULD fall through to YAML")
    void given_spiDeclines_when_resolving_should_fallThroughToYaml() {
        MergeSourceProvider provider = r -> Optional.empty();

        var resolver = new MergeSpecResolver(
                propertiesWithRoute("/v1/orders", "yaml.A", "yaml.B"), List.of(provider), new GrpcRouteRegistry());

        assertThat(resolver.resolve(route("/v1/orders"))
                        .orElseThrow()
                        .sources()
                        .get(0)
                        .fullServiceName())
                .isEqualTo("yaml.A");
    }

    @Test
    @DisplayName("GIVEN more sources than the configured maximum WHEN resolving SHOULD fail at registration")
    void given_tooManySources_when_resolving_should_failAtRegistration() {
        var props = new GatewayProperties();
        props.getMerge().setMaxSources(2);
        MergeSourceProvider provider = r -> Optional.of(new MergeSpec(
                List.of(
                        MergeSource.unsorted("a.A", "list"),
                        MergeSource.unsorted("b.B", "list"),
                        MergeSource.unsorted("c.C", "list")),
                "id",
                null,
                null,
                0));

        var resolver = new MergeSpecResolver(props, List.of(provider), new GrpcRouteRegistry());

        assertThatThrownBy(() -> resolver.resolve(route("/v1/orders")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over the limit");
    }

    @Test
    @DisplayName("GIVEN the same source listed twice WHEN resolving SHOULD reject it, since every row would double")
    void given_duplicateSource_when_resolving_should_reject() {
        MergeSourceProvider provider = r -> Optional.of(new MergeSpec(
                List.of(MergeSource.unsorted("a.A", "list"), MergeSource.unsorted("a.A", "list")),
                "id",
                null,
                null,
                0));

        var resolver = new MergeSpecResolver(new GatewayProperties(), List.of(provider), new GrpcRouteRegistry());

        assertThatThrownBy(() -> resolver.resolve(route("/v1/orders")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same source twice");
    }

    @Test
    @DisplayName("GIVEN the same proto service under two endpoints WHEN resolving SHOULD accept it as distinct sources")
    void given_sameServiceDifferentEndpoints_when_resolving_should_accept() {
        MergeSourceProvider provider = r -> Optional.of(new MergeSpec(
                List.of(
                        new MergeSource("orders-eu", "orders.OrderService", "list", true),
                        new MergeSource("orders-us", "orders.OrderService", "list", false)),
                "id",
                null,
                null,
                0));

        var resolver = new MergeSpecResolver(new GatewayProperties(), List.of(provider), new GrpcRouteRegistry());

        // The sharded case: identical .proto, different deployments. Distinguished by endpoint.
        assertThat(resolver.resolve(route("/v1/orders"))).isPresent();
    }

    @Test
    @DisplayName(
            "GIVEN a spec with no tie-break field WHEN resolving SHOULD still accept it, warning rather than failing")
    void given_noTieBreakField_when_resolving_should_accept() {
        MergeSourceProvider provider = r -> Optional.of(new MergeSpec(
                List.of(MergeSource.unsorted("a.A", "list"), MergeSource.unsorted("b.B", "list")), "", null, null, 0));

        var resolver = new MergeSpecResolver(new GatewayProperties(), List.of(provider), new GrpcRouteRegistry());

        // Legal — the caller's sort may already be unique — so it warns rather than blocking startup.
        assertThat(resolver.resolve(route("/v1/orders"))).isPresent();
    }

    @Test
    @DisplayName("GIVEN a route with no REST binding WHEN resolving from YAML SHOULD not match on path")
    void given_noRestBinding_when_resolvingYaml_should_returnEmpty() {
        var graphQlOnly = RouteDefinition.builder(
                        "orders.OrderService", "list", MethodDescriptor.MethodType.UNARY, "localhost:1")
                .graphQl("orders", RouteDefinition.GraphQlOperationType.QUERY)
                .build();

        var resolver = new MergeSpecResolver(
                propertiesWithRoute("/v1/orders", "a.A", "b.B"), List.of(), new GrpcRouteRegistry());

        assertThat(resolver.resolve(graphQlOnly)).isEmpty();
    }
}
