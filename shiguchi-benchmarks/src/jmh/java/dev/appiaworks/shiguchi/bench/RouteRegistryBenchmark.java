package dev.appiaworks.shiguchi.bench;

import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures GrpcRouteRegistry hot-path operations:
 *  - contextFor(route): was O(n·m) linear scan, now O(1) map lookup (P1-C)
 *  - findByHttpKey: exact and template path lookup
 *
 * Setup: {@code routes} routes spread over services of 20 routes each — 200 (the original
 * fixture), 2 000 and 20 000 — so the "O(1)" claim is checked against registry size rather than
 * assumed: the lookups should stay flat across the three columns, and
 * {@code findByHttpKey_template} (a path with a variable, which cannot be an exact-map hit) is
 * the one that would reveal a scan.
 */
// Nanosecond granularity: these are O(1) map lookups, well under a microsecond.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RouteRegistryBenchmark {

    @Param({"200", "2000", "20000"})
    public int routes;

    private GrpcRouteRegistry registry;
    private RouteDefinition sampleRoute;
    private String samplePath;
    private String sampleTemplatePath;
    private String sampleGraphQlName;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        registry = new GrpcRouteRegistry();
        int services = Math.max(1, routes / 20);
        int sampleSvc = services / 2;

        for (int svc = 0; svc < services; svc++) {
            dev.appiaworks.shiguchi.config.ServiceDefinition def =
                    new dev.appiaworks.shiguchi.config.ServiceDefinition();
            def.setName("svc-" + svc);
            def.setHost("localhost");
            def.setPort(9000 + (svc % 1000));

            // No channel: the registry never touches it, and 1 000 real channels would only
            // measure socket setup.
            ServiceEndpointContext ctx = new ServiceEndpointContext(def, null);

            for (int m = 0; m < 20; m++) {
                // Every other method takes a path variable, so half the routes are templates.
                boolean templated = m % 2 == 1;
                String path = "/v1/svc" + svc + "/method" + m + (templated ? "/{id}" : "");
                RouteDefinition route = RouteDefinition.builder(
                                "com.example.Svc" + svc,
                                "Method" + m,
                                io.grpc.MethodDescriptor.MethodType.UNARY,
                                "localhost:" + (9000 + (svc % 1000)))
                        .httpBinding("GET", path)
                        .graphQl("svc" + svc + "Method" + m, RouteDefinition.GraphQlOperationType.QUERY)
                        .build();
                ctx.addRoute(route);
                if (svc == sampleSvc && m == 10) {
                    sampleRoute = route;
                    samplePath = path;
                    sampleGraphQlName = "svc" + svc + "Method" + m;
                }
                if (svc == sampleSvc && m == 11) sampleTemplatePath = "/v1/svc" + svc + "/method" + m + "/42";
            }

            registry.registerContext(ctx);
        }

        if (registry.findByHttpKey("GET", samplePath).isEmpty()
                || registry.findByHttpKey("GET", sampleTemplatePath).isEmpty()) {
            throw new IllegalStateException("sample routes not resolvable");
        }
    }

    @Benchmark
    public ServiceEndpointContext contextFor_route() {
        return registry.contextFor(sampleRoute);
    }

    @Benchmark
    public Optional<RouteDefinition> findByHttpKey_exact() {
        return registry.findByHttpKey("GET", samplePath);
    }

    /** A request path that only a template route matches — the case a scan would show up in. */
    @Benchmark
    public Optional<RouteDefinition> findByHttpKey_template() {
        return registry.findByHttpKey("GET", sampleTemplatePath);
    }

    @Benchmark
    public List<RouteDefinition> findByGraphQlName() {
        return registry.findByGraphQlName(sampleGraphQlName);
    }
}
