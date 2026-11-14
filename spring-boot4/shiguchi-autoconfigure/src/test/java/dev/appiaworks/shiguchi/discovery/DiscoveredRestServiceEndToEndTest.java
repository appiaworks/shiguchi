package dev.appiaworks.shiguchi.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcProxyHandlerRegistry;
import dev.appiaworks.shiguchi.routing.DynamicRouteLoader;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RestDownstreamRouteLoader;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.routing.ServiceLoadStatus;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

/**
 * Drives {@link DiscoveredServiceSynchronizer} with the real {@link RestDownstreamRouteLoader}
 * against a stub REST service that serves an OpenAPI document over HTTP.
 */
@DisplayName("DiscoveredServiceSynchronizer REST end to end")
class DiscoveredRestServiceEndToEndTest {

    private static final String SPEC = """
            {"openapi":"3.0.3","info":{"title":"Billing","version":"1"},
             "paths":{"/invoices":{"get":{"operationId":"listInvoices",
               "responses":{"200":{"description":"ok","content":{"application/json":{"schema":
                 {"type":"array","items":{"type":"string"}}}}}}}}}}
            """;

    private HttpServer server;
    private final AtomicInteger docRequests = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private int startStubService() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/api-docs", ex -> {
            docRequests.incrementAndGet();
            byte[] body = SPEC.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static class MutableClient implements DiscoveryClient {
        final Map<String, List<ServiceInstance>> services = new LinkedHashMap<>();

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            return services.getOrDefault(serviceId, List.of());
        }

        @Override
        public List<String> getServices() {
            return new ArrayList<>(services.keySet());
        }
    }

    private DiscoveredServiceSynchronizer synchronizer(
            GatewayProperties props, GrpcRouteRegistry registry, MutableClient client) {
        RestDownstreamRouteLoader rest =
                new RestDownstreamRouteLoader(props, registry, Optional.empty(), Optional.empty());
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("client", client);
        bf.registerSingleton("rest", rest);
        return new DiscoveredServiceSynchronizer(
                props,
                bf.getBeanProvider(DiscoveryClient.class),
                mock(DynamicRouteLoader.class),
                bf.getBeanProvider(RestDownstreamRouteLoader.class),
                registry,
                Optional.empty(),
                new ServiceLoadStatus(),
                new AutoRegisteredServices(),
                bf.getBeanProvider(SchemaReloadListener.class),
                bf.getBeanProvider(GrpcProxyHandlerRegistry.class));
    }

    @Test
    @DisplayName("GIVEN a stub REST service serving an OpenAPI doc WHEN synced, then dropped for 3 syncs"
            + " THEN SHOULD register its routes from the document, then remove them")
    void GIVEN_rest_service_WHEN_registered_then_dropped_SHOULD_add_then_remove_routes() throws Exception {
        int port = startStubService();
        GatewayProperties props = new GatewayProperties();
        props.getDiscovery().getAutoRegister().setEnabled(true);
        props.getDiscovery().getAutoRegister().setRemovalGraceSyncs(3);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        MutableClient client = new MutableClient();
        client.services.put(
                "billing",
                List.of(new DefaultServiceInstance(
                        "billing-1",
                        "billing",
                        "127.0.0.1",
                        port,
                        false,
                        Map.of("shiguchi.expose", "true", "shiguchi.type", "rest"))));
        DiscoveredServiceSynchronizer sync = synchronizer(props, registry, client);

        sync.sync();

        assertThat(sync.seen().get("billing").outcome()).isEqualTo(DiscoveredServiceSynchronizer.Outcome.REGISTERED);
        ServiceEndpointContext ctx = registry.contextByServiceName("billing").orElseThrow();
        assertThat(ctx.getConfig().getMode()).isEqualTo(GatewayMode.REST_DOWNSTREAM);
        assertThat(ctx.getConfig().effectiveBaseUrl()).isEqualTo("http://127.0.0.1:" + port);
        assertThat(ctx.getRoutes()).isNotEmpty();
        assertThat(docRequests.get()).isEqualTo(1);

        sync.sync();
        assertThat(docRequests.get())
                .as("a registered service is not refetched on every sync")
                .isEqualTo(1);

        client.services.clear();
        sync.sync();
        sync.sync();
        assertThat(registry.contextByServiceName("billing"))
                .as("within the grace period")
                .isPresent();
        sync.sync();

        assertThat(registry.contextByServiceName("billing")).isEmpty();
        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an openapi-url on a foreign host WHEN synced THEN SHOULD not fetch it")
    void GIVEN_foreign_openapi_url_WHEN_synced_SHOULD_not_fetch() throws Exception {
        int port = startStubService();
        GatewayProperties props = new GatewayProperties();
        props.getDiscovery().getAutoRegister().setEnabled(true);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        MutableClient client = new MutableClient();
        // The stub is reachable at 127.0.0.1, but the instance advertises another host.
        client.services.put(
                "billing",
                List.of(new DefaultServiceInstance(
                        "billing-1",
                        "billing",
                        "10.9.9.9",
                        port,
                        false,
                        Map.of(
                                "shiguchi.expose",
                                "true",
                                "openapi-url",
                                "http://127.0.0.1:" + port + "/v3/api-docs"))));
        DiscoveredServiceSynchronizer sync = synchronizer(props, registry, client);

        sync.sync();

        assertThat(sync.seen().get("billing").outcome())
                .isEqualTo(DiscoveredServiceSynchronizer.Outcome.FOREIGN_OPENAPI_HOST);
        assertThat(docRequests.get()).isZero();
        assertThat(registry.contextByServiceName("billing")).isEmpty();
    }
}
