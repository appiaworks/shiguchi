package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for {@link RestBatchDataLoaderRegistrar}.
 *
 * <p>The registrar only scans; registration with Spring GraphQL is {@link GrpcDataLoaderRegistryFactory}'s job.
 */
@DisplayName("RestBatchDataLoaderRegistrar")
class RestBatchDataLoaderRegistrarTest {

    private static ServiceDefinition restDownstreamService(
            String name, String baseUrl, RestBatchLoaderConfig... loaders) {
        ServiceDefinition svcDef = new ServiceDefinition();
        svcDef.setName(name);
        svcDef.setMode(GatewayMode.REST_DOWNSTREAM);
        svcDef.setBaseUrl(baseUrl);
        svcDef.setRestBatchLoaders(List.of(loaders));
        return svcDef;
    }

    private static RestBatchLoaderConfig loaderConfig(String type, String endpoint) {
        RestBatchLoaderConfig cfg = new RestBatchLoaderConfig();
        cfg.setType(type);
        cfg.setEndpoint(endpoint);
        return cfg;
    }

    private static GatewayProperties propsWith(ServiceDefinition... services) {
        GatewayProperties props = new GatewayProperties();
        props.setServices(List.of(services));
        return props;
    }

    @Test
    @DisplayName("Given no services are REST_DOWNSTREAM, when scan runs, should register no loaders")
    void given_noRestDownstreamServices_when_scan_should_registerNoLoaders() {
        // Arrange
        ServiceDefinition grpcService = new ServiceDefinition();
        grpcService.setName("inv-grpc");
        grpcService.setMode(GatewayMode.DYNAMIC);
        GatewayProperties props = propsWith(grpcService);
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given a REST_DOWNSTREAM service with no restBatchLoaders declared, when scan runs, should register no loaders")
    void given_restDownstreamServiceWithNoLoaders_when_scan_should_registerNoLoaders() {
        // Arrange
        ServiceDefinition svcDef = restDownstreamService("inventory", "http://inventory:8080");
        GatewayProperties props = propsWith(svcDef);
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given a REST_DOWNSTREAM service with one restBatchLoader, when scan runs, should register one loader keyed by its GraphQL type")
    void given_oneRestBatchLoaderDeclared_when_scan_should_registerOneLoaderByType() {
        // Arrange
        RestBatchLoaderConfig cfg = loaderConfig("Product", "/products");
        ServiceDefinition svcDef = restDownstreamService("catalog", "http://catalog:8080", cfg);
        GatewayProperties props = propsWith(svcDef);
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).containsOnlyKeys("Product");
    }

    @Test
    @DisplayName("Given a restBatchLoader entry with a blank type, when scan runs, should skip it without throwing")
    void given_loaderWithBlankType_when_scan_should_skipWithoutThrowing() {
        // Arrange
        RestBatchLoaderConfig blank = loaderConfig("", "/whatever");
        RestBatchLoaderConfig valid = loaderConfig("Category", "/categories");
        ServiceDefinition svcDef = restDownstreamService("catalog", "http://catalog:8080", blank, valid);
        GatewayProperties props = propsWith(svcDef);
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);

        // Act — must not throw
        registrar.scan();

        // Assert — only the valid entry registered
        assertThat(registrar.getLoaders()).containsOnlyKeys("Category");
    }

    @Test
    @DisplayName(
            "Given two REST_DOWNSTREAM services each with a loader, when scan runs, should register loaders for both, keyed by their own type")
    void given_twoServicesWithLoaders_when_scan_should_registerBoth() {
        // Arrange
        ServiceDefinition svc1 =
                restDownstreamService("catalog", "http://catalog:8080", loaderConfig("Product", "/products"));
        ServiceDefinition svc2 =
                restDownstreamService("accounts", "http://accounts:9090", loaderConfig("User", "/users"));
        GatewayProperties props = propsWith(svc1, svc2);
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).containsOnlyKeys("Product", "User");
    }

    @Test
    @DisplayName(
            "Given getLoaders is called, when the returned map is modified, should throw UnsupportedOperationException (unmodifiable view)")
    void given_getLoadersCalled_when_returnedMapModified_should_throwUnsupportedOperationException() {
        // Arrange
        ServiceDefinition svcDef =
                restDownstreamService("catalog", "http://catalog:8080", loaderConfig("Product", "/products"));
        GatewayProperties props = propsWith(svcDef);
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);
        registrar.scan();

        // Act & Assert
        Map<String, BatchLoader> loaders = registrar.getLoaders();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loaders.put("Other", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------------------
    // Through the pipeline, against a real HTTP server
    // ---------------------------------------------------------------------------

    /** Answers every batch GET with one entity per requested id and records the query strings. */
    private static com.sun.net.httpserver.HttpServer productServer(List<String> queries) throws java.io.IOException {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/products", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            queries.add(query);
            StringBuilder body = new StringBuilder("[");
            String ids = query.substring(query.indexOf("ids=") + 4);
            boolean first = true;
            for (String id : ids.split(",")) {
                if (!first) body.append(',');
                first = false;
                body.append("{\"id\":\"").append(id).append("\"}");
            }
            byte[] bytes = body.append(']').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static BatchLoaderPipeline pipelineOver(
            RestBatchDataLoaderRegistrar registrar, dev.appiaworks.shiguchi.config.DataLoaderConfig config) {
        GrpcBatchDataLoaderRegistrar grpc = org.mockito.Mockito.mock(GrpcBatchDataLoaderRegistrar.class);
        org.mockito.Mockito.when(grpc.getLoaders()).thenReturn(Map.of());
        return new BatchLoaderPipeline(grpc, registrar, config);
    }

    private static RestBatchDataLoaderRegistrar scannedRegistrar(int port, RestBatchLoaderConfig cfg) {
        cfg.setBatchEndpoint(true);
        cfg.setIdsParam("ids");
        cfg.setKeyField("id");
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(
                propsWith(restDownstreamService("catalog", "http://127.0.0.1:" + port, cfg)));
        registrar.scan();
        return registrar;
    }

    @Test
    @DisplayName(
            "Given a REST loader with max-batch-size 50, when 120 keys load through the pipeline, should send three GETs")
    void given_restLoaderMaxBatchSize50_when_120KeysLoaded_should_sendThreeGets() throws Exception {
        List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = productServer(queries);
        try {
            RestBatchLoaderConfig cfg = loaderConfig("Product", "/products");
            cfg.setMaxBatchSize(50);
            RestBatchDataLoaderRegistrar registrar =
                    scannedRegistrar(server.getAddress().getPort(), cfg);
            BatchLoaderPipeline pipeline =
                    pipelineOver(registrar, new dev.appiaworks.shiguchi.config.DataLoaderConfig());
            List<String> keys = new java.util.ArrayList<>();
            for (int i = 0; i < 120; i++) keys.add("p" + i);

            Map<String, Map<String, Object>> result =
                    pipeline.load("Product", keys, new io.grpc.Metadata()).block();

            assertThat(queries).hasSize(3);
            assertThat(result).hasSize(120);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName(
            "Given a cache entry for a REST type, when the same keys load twice through the pipeline, should make one HTTP exchange")
    void given_restTypeWithCacheEntry_when_sameKeysLoadedTwice_should_makeOneExchange() throws Exception {
        List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = productServer(queries);
        try {
            RestBatchDataLoaderRegistrar registrar =
                    scannedRegistrar(server.getAddress().getPort(), loaderConfig("Product", "/products"));
            dev.appiaworks.shiguchi.config.DataLoaderConfig config =
                    new dev.appiaworks.shiguchi.config.DataLoaderConfig();
            config.getCache().put("Product", new dev.appiaworks.shiguchi.config.DataLoaderConfig.TypeCache());
            BatchLoaderPipeline pipeline = pipelineOver(registrar, config);

            pipeline.load("Product", List.of("1", "2"), new io.grpc.Metadata()).block();
            Map<String, Map<String, Object>> second = pipeline.load(
                            "Product", List.of("1", "2"), new io.grpc.Metadata())
                    .block();

            assertThat(queries).hasSize(1);
            assertThat(second).containsOnlyKeys("1", "2");
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------------
    // One-to-many (group-by)
    // ---------------------------------------------------------------------------

    private static RestBatchLoaderConfig groupedConfig(String type, String endpoint, String idsParam, String groupBy) {
        RestBatchLoaderConfig cfg = loaderConfig(type, endpoint);
        cfg.setIdsParam(idsParam);
        cfg.setGroupBy(groupBy);
        cfg.setResultField("items");
        return cfg;
    }

    /** Answers GET /orders?customerIds=a,b and GET /customers/{id}/orders with two orders per customer. */
    private static com.sun.net.httpserver.HttpServer orderServer(List<String> requests) throws java.io.IOException {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(query == null ? path : path + "?" + query);
            List<String> ids;
            if (path.equals("/orders")) {
                ids = List.of(query.substring(query.indexOf('=') + 1).split(","));
            } else if (path.startsWith("/customers/missing")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            } else {
                ids = List.of(path.split("/")[2]);
            }
            StringBuilder body = new StringBuilder("{\"items\":[");
            boolean first = true;
            for (String id : ids) {
                for (int n = 1; n <= 2; n++) {
                    if (!first) body.append(',');
                    first = false;
                    body.append("{\"id\":\"o").append(n).append("-").append(id);
                    body.append("\",\"customerId\":\"").append(id).append("\"}");
                }
            }
            byte[] bytes = body.append("]}").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static BatchLoaderPipeline groupedPipeline(
            com.sun.net.httpserver.HttpServer server, RestBatchLoaderConfig cfg, int maxChildren) {
        GatewayProperties props = propsWith(restDownstreamService(
                "shop", "http://127.0.0.1:" + server.getAddress().getPort(), cfg));
        props.getGraphql().getDataLoader().setMaxChildrenPerKey(maxChildren);
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);
        registrar.scan();
        return pipelineOver(registrar, new dev.appiaworks.shiguchi.config.DataLoaderConfig());
    }

    @Test
    @DisplayName("GIVEN a by-id and a group-by entry for one type WHEN scan runs SHOULD keep both apart")
    void GIVEN_byIdAndGroupByEntryOfOneType_WHEN_scan_SHOULD_keepBothApart() {
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(propsWith(restDownstreamService(
                "shop",
                "http://127.0.0.1:1",
                loaderConfig("Order", "/orders"),
                groupedConfig("Order", "/orders", "customerIds", "customerId"))));

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("Order");
        assertThat(registrar.getGroupedLoaders()).containsOnlyKeys("Order#by:customerId");
    }

    @Test
    @DisplayName("GIVEN a snake_case group-by WHEN scan runs SHOULD name the loader by its camelCase")
    void GIVEN_snakeCaseGroupBy_WHEN_scan_SHOULD_nameLoaderByCamelCase() {
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(propsWith(restDownstreamService(
                "shop", "http://127.0.0.1:1", groupedConfig("Order", "/orders", "customerIds", "customer_id"))));

        registrar.scan();

        assertThat(registrar.getGroupedLoaders()).containsOnlyKeys("Order#by:customerId");
    }

    @Test
    @DisplayName(
            "GIVEN a group-by loader WHEN parents load through the pipeline SHOULD send one GET and group the children")
    void GIVEN_groupByLoader_WHEN_parentsLoaded_SHOULD_sendOneGetAndGroup() throws Exception {
        List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = orderServer(requests);
        try {
            BatchLoaderPipeline pipeline =
                    groupedPipeline(server, groupedConfig("Order", "/orders", "customerIds", "customerId"), 0);

            GroupedOutcome outcome = pipeline.loadGrouped(
                            "Order#by:customerId", List.of("c1", "c2", "c3", "c1"), new io.grpc.Metadata())
                    .block();

            assertThat(requests).containsExactly("/orders?customerIds=c1,c2,c3");
            assertThat(outcome.groups()).containsOnlyKeys("c1", "c2", "c3");
            assertThat(outcome.groups().get("c2")).hasSize(2);
            assertThat(pipeline.groupedNames()).containsExactly("Order#by:customerId");
            assertThat(pipeline.hasGrouped("Order#by:customerId")).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("GIVEN a group-by loader with max-batch-size 2 WHEN 5 parents load SHOULD send three GETs")
    void GIVEN_groupByLoaderMaxBatchSize2_WHEN_fiveParentsLoaded_SHOULD_sendThreeGets() throws Exception {
        List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = orderServer(requests);
        try {
            RestBatchLoaderConfig cfg = groupedConfig("Order", "/orders", "customerIds", "customerId");
            cfg.setMaxBatchSize(2);
            BatchLoaderPipeline pipeline = groupedPipeline(server, cfg, 0);

            GroupedOutcome outcome = pipeline.loadGrouped(
                            "Order#by:customerId", List.of("a", "b", "c", "d", "e"), new io.grpc.Metadata())
                    .block();

            assertThat(requests).hasSize(3);
            assertThat(outcome.groups()).hasSize(5);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName(
            "GIVEN the parallel strategy WHEN parents load SHOULD send one GET per id and treat 404 as no children")
    void GIVEN_parallelStrategy_WHEN_parentsLoaded_SHOULD_oneGetPerIdAnd404NoChildren() throws Exception {
        List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = orderServer(requests);
        try {
            RestBatchLoaderConfig cfg = groupedConfig("Order", "/customers/{id}/orders", "ids", "customerId");
            cfg.setBatchEndpoint(false);
            BatchLoaderPipeline pipeline = groupedPipeline(server, cfg, 0);

            GroupedOutcome outcome = pipeline.loadGrouped(
                            "Order#by:customerId", List.of("c1", "missing"), new io.grpc.Metadata())
                    .block();

            assertThat(requests).containsExactlyInAnyOrder("/customers/c1/orders", "/customers/missing/orders");
            assertThat(outcome.groups().get("c1")).hasSize(2);
            assertThat(outcome.groups().get("missing")).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("GIVEN max-children-per-key 1 WHEN a REST parent has two children SHOULD fail that key")
    void GIVEN_maxChildrenPerKey_WHEN_restParentOverCap_SHOULD_failKey() throws Exception {
        List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = orderServer(requests);
        try {
            BatchLoaderPipeline pipeline =
                    groupedPipeline(server, groupedConfig("Order", "/orders", "customerIds", "customerId"), 1);

            GroupedOutcome outcome = pipeline.loadGrouped("Order#by:customerId", List.of("c1"), new io.grpc.Metadata())
                    .block();

            assertThat(outcome.failures()).containsOnlyKeys("c1");
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------------
    // Rescan
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN an unchanged config WHEN scan runs again SHOULD keep the same loader instances")
    void GIVEN_unchangedConfig_WHEN_scanAgain_SHOULD_keepLoaderInstances() {
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(propsWith(restDownstreamService(
                "shop",
                "http://127.0.0.1:1",
                loaderConfig("Product", "/products"),
                groupedConfig("Order", "/orders", "customerIds", "customerId"))));
        registrar.scan();
        BatchLoader byId = registrar.getLoaders().get("Product");
        GroupedOutcome.Loader many = registrar.getGroupedLoaders().get("Order#by:customerId");

        registrar.scan();

        assertThat(registrar.getLoaders().get("Product")).isSameAs(byId);
        assertThat(registrar.getGroupedLoaders().get("Order#by:customerId")).isSameAs(many);
    }

    @Test
    @DisplayName("GIVEN a service added after the first scan WHEN scan runs again SHOULD register its loaders")
    void GIVEN_serviceAddedAfterFirstScan_WHEN_scanAgain_SHOULD_registerItsLoaders() {
        GatewayProperties props =
                propsWith(restDownstreamService("shop", "http://127.0.0.1:1", loaderConfig("A", "/a")));
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);
        registrar.scan();
        List<ServiceDefinition> services = new java.util.ArrayList<>(props.getServices());
        services.add(restDownstreamService(
                "late", "http://127.0.0.1:2", groupedConfig("Order", "/orders", "ids", "customerId")));
        props.setServices(services);

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("A");
        assertThat(registrar.getGroupedLoaders()).containsOnlyKeys("Order#by:customerId");
    }

    @Test
    @DisplayName("GIVEN a changed and a removed entry WHEN scan runs again SHOULD replace the one and drop the other")
    void GIVEN_changedAndRemovedEntry_WHEN_scanAgain_SHOULD_replaceAndDrop() {
        RestBatchLoaderConfig a = loaderConfig("A", "/a");
        GatewayProperties props =
                propsWith(restDownstreamService("shop", "http://127.0.0.1:1", a, loaderConfig("B", "/b")));
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);
        registrar.scan();
        BatchLoader before = registrar.getLoaders().get("A");
        a.setEndpoint("/a2");
        props.getServices().get(0).setRestBatchLoaders(List.of(a));

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("A");
        assertThat(registrar.getLoaders().get("A")).isNotSameAs(before);
    }

    @Test
    @DisplayName("GIVEN loaders declared after the pipeline was built WHEN rescanRest runs SHOULD see them")
    void GIVEN_loadersDeclaredLate_WHEN_rescanRest_SHOULD_seeThem() {
        GatewayProperties props = new GatewayProperties();
        props.setServices(List.of());
        RestBatchDataLoaderRegistrar registrar = new RestBatchDataLoaderRegistrar(props);
        registrar.scan();
        BatchLoaderPipeline pipeline = pipelineOver(registrar, new dev.appiaworks.shiguchi.config.DataLoaderConfig());
        assertThat(pipeline.groupedNames()).isEmpty();
        props.setServices(List.of(restDownstreamService(
                "shop", "http://127.0.0.1:1", groupedConfig("Order", "/orders", "ids", "customerId"))));

        pipeline.rescanRest();

        assertThat(pipeline.groupedNames()).containsExactly("Order#by:customerId");
    }
}
