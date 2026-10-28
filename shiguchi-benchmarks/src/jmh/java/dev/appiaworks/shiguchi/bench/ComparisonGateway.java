package dev.appiaworks.shiguchi.bench;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * This gateway as the side-by-side comparison runs it ({@code comparison/compare.sh}): the
 * configuration of {@link GatewayHttpBenchmark}'s {@code default} profile — DYNAMIC discovery of
 * {@code bench.Orders} at {@code $BACKEND_HOST:$BACKEND_PORT}, auto-mapped REST routes, the UI,
 * GraphQL and OpenAPI surfaces off — listening on {@code $PORT} (default 8080). Set
 * {@code GATEWAY_PROPS} to a comma-separated list of extra {@code key=value} properties.
 *
 * <pre>java -cp shiguchi-benchmarks-*-jmh.jar dev.appiaworks.shiguchi.bench.ComparisonGateway</pre>
 */
public final class ComparisonGateway {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class GatewayApp {}

    public static void main(String[] args) {
        var env = System.getenv();
        java.util.List<String> properties = new java.util.ArrayList<>(java.util.List.of(
                "server.port=" + env.getOrDefault("PORT", "8080"),
                "spring.main.banner-mode=off",
                "logging.level.root=WARN",
                "shiguchi.ui.enabled=false",
                "shiguchi.graphql.enabled=false",
                "shiguchi.openapi.enabled=false",
                "shiguchi.rest.auto-mapping=true",
                "shiguchi.services[0].name=orders",
                "shiguchi.services[0].mode=DYNAMIC",
                "shiguchi.services[0].host=" + env.getOrDefault("BACKEND_HOST", "localhost"),
                "shiguchi.services[0].port=" + env.getOrDefault("BACKEND_PORT", "9090")));
        String extra = env.getOrDefault("GATEWAY_PROPS", "");
        for (String p : extra.split(",")) {
            if (!p.isBlank()) properties.add(p.trim());
        }
        new SpringApplicationBuilder(GatewayApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(properties.toArray(String[]::new))
                .run(args);
    }
}
