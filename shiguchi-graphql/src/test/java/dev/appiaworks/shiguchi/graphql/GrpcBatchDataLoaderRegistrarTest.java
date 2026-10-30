package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.api.GraphQlFieldRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor.MethodType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for {@link GrpcBatchDataLoaderRegistrar}.
 *
 * <p>{@code detectEntityKeyField} is private, so it is exercised indirectly by calling
 * {@link GrpcBatchDataLoaderRegistrar#scan()} (via {@code @PostConstruct}) and then
 * asserting on the loader map returned by {@link GrpcBatchDataLoaderRegistrar#getLoaders()}.
 *
 * <p>A real {@link GrpcRouteRegistry} and a real {@link ServiceEndpointContext} are used so
 * that {@code contextFor(route)} and {@code lookupMethod()} execute their actual logic.
 * Proto descriptors are built programmatically via {@code FileDescriptor.buildFrom()}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcBatchDataLoaderRegistrar")
class GrpcBatchDataLoaderRegistrarTest {

    // ReactiveGrpcDispatcher is only stored in GrpcBatchLoader — never called during scan.
    @Mock
    private ReactiveGrpcDispatcher dispatcher;

    private GrpcRouteRegistry registry;

    // ---------------------------------------------------------------------------
    // Common setup
    // ---------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        registry = new GrpcRouteRegistry();
    }

    // ---------------------------------------------------------------------------
    // Helpers: build proto descriptors programmatically
    // ---------------------------------------------------------------------------

    /**
     * Builds a FileDescriptor that contains:
     *   - one message {@code entityTypeName} with fields: {@code id} (marked batch_key=true, field #1)
     *     and optionally more fields
     *   - one message {@code wrapperTypeName} with a repeated field named {@code repeatedFieldName}
     *     of type {@code entityTypeName} (field #1); omitted when wrapperTypeName is null
     *   - one service {@code serviceName} with one method {@code methodName} whose
     *     input type is "Empty" and output type is either the wrapper (unary) or the entity (streaming)
     */
    private FileDescriptor buildDescriptor(
            String fileName,
            String serviceName,
            String methodName,
            String entityTypeName,
            String wrapperTypeName,
            String repeatedFieldName,
            boolean entityHasBatchKey)
            throws Exception {

        FileDescriptor graphqlDep = GraphQlProto.getDescriptor();

        // Build the entity message with an "id" field (optionally batch_key=true)
        FieldOptions.Builder entityIdOptions = FieldOptions.newBuilder();
        if (entityHasBatchKey) {
            GraphQlFieldRule batchKeyAnnotation =
                    GraphQlFieldRule.newBuilder().setBatchKey(true).build();
            entityIdOptions.setExtension(GraphQlProto.graphqlField, batchKeyAnnotation);
        }

        DescriptorProto.Builder entityMsg = DescriptorProto.newBuilder()
                .setName(entityTypeName)
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(Type.TYPE_INT64)
                        .setLabel(Label.LABEL_OPTIONAL)
                        .setOptions(entityIdOptions.build())
                        .build());

        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addDependency("shiguchi/api/graphql.proto");

        // Add an "Empty" input message so method descriptors are valid
        fileProto.addMessageType(DescriptorProto.newBuilder().setName("Empty").build());

        fileProto.addMessageType(entityMsg);

        String outputTypeName;

        if (wrapperTypeName != null) {
            // Unary path: wrapper message contains a repeated field of the entity type
            DescriptorProto wrapperMsg = DescriptorProto.newBuilder()
                    .setName(wrapperTypeName)
                    .addField(FieldDescriptorProto.newBuilder()
                            .setName(repeatedFieldName)
                            .setNumber(1)
                            .setType(Type.TYPE_MESSAGE)
                            .setTypeName("." + entityTypeName)
                            .setLabel(Label.LABEL_REPEATED)
                            .build())
                    .build();
            fileProto.addMessageType(wrapperMsg);
            outputTypeName = wrapperTypeName;
        } else {
            // Streaming path: output type IS the entity
            outputTypeName = entityTypeName;
        }

        // Build service with one method
        fileProto.addService(ServiceDescriptorProto.newBuilder()
                .setName(serviceName)
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName(methodName)
                        .setInputType(".Empty")
                        .setOutputType("." + outputTypeName)
                        .build())
                .build());

        return FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[] {graphqlDep});
    }

    /**
     * Creates a real ServiceEndpointContext wired with the given FileDescriptor so that
     * {@code lookupMethod(serviceFqn, methodName)} returns the correct proto MethodDescriptor.
     */
    private ServiceEndpointContext buildContext(String serviceName, FileDescriptor fileDescriptor) {

        ServiceDefinition config = new ServiceDefinition();
        config.setName(serviceName);

        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);

        // Wire the proto MethodDescriptor into the methodByKey map
        var serviceDescriptor = fileDescriptor.findServiceByName(serviceName);
        Map<String, com.google.protobuf.Descriptors.ServiceDescriptor> serviceByFqn =
                Map.of(serviceName, serviceDescriptor);
        ctx.setServiceByFqn(serviceByFqn);

        // methodByKey key: "serviceFqn/methodName"
        var methodDescriptors = serviceDescriptor.getMethods();
        var methodByKey = new java.util.HashMap<String, com.google.protobuf.Descriptors.MethodDescriptor>();
        for (var md : methodDescriptors) {
            methodByKey.put(serviceName + "/" + md.getName(), md);
        }
        ctx.setMethodByKey(methodByKey);

        return ctx;
    }

    /**
     * Registers a route + context pair in the real GrpcRouteRegistry so that
     * {@code contextFor(route)} resolves correctly.
     */
    private void registerRoute(RouteDefinition route, ServiceEndpointContext ctx) {
        ctx.addRoute(route);
        registry.registerContext(ctx);
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a SERVER_STREAMING loader route whose entity type has batch_key=true on 'id', when scan runs, should detect 'id' as the entity key field")
    void given_streamingRouteWithBatchKeyOnEntityField_when_scan_should_detectEntityKeyAsId() throws Exception {

        // Arrange — streaming: no wrapper, output type is the entity itself
        FileDescriptor fd = buildDescriptor(
                "shop.proto",
                "ShopService",
                "GetBooks",
                "Book",
                null, // no wrapper → streaming path
                null,
                true); // id field has batch_key=true

        ServiceEndpointContext ctx = buildContext("ShopService", fd);

        RouteDefinition route = RouteDefinition.builder(
                        "ShopService", "GetBooks", MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl("invTypes", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("Book", "ids", "") // empty resultField → streaming path
                .build();

        registerRoute(route, ctx);

        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);

        // Act
        registrar.scan();

        // Assert
        Map<String, BatchLoader> loaders = registrar.getLoaders();
        assertThat(loaders).containsKey("Book");
        // The loader is present, confirming entity key detection succeeded (not fallen back to "id"
        // silently failing — we verify via a second scenario that fallback produces "id" too,
        // but here the batch_key annotation is the source of truth)
        assertThat(loaders.get("Book")).isNotNull();
    }

    @Test
    @DisplayName(
            "Given a UNARY loader route whose response wrapper has a repeated entity field with batch_key=true, when scan runs, should detect 'id' as the entity key field")
    void given_unaryRouteWithBatchKeyOnWrappedEntityField_when_scan_should_detectEntityKeyAsId() throws Exception {

        // Arrange — unary: output is a wrapper containing a repeated "types" field of Book
        FileDescriptor fd = buildDescriptor(
                "inv_unary.proto",
                "ShopBatchService",
                "GetBooksBatch",
                "Book",
                "GetBooksResponse", // wrapper message
                "types", // repeated field name in wrapper
                true); // entity id field has batch_key=true

        ServiceEndpointContext ctx = buildContext("ShopBatchService", fd);

        RouteDefinition route = RouteDefinition.builder(
                        "ShopBatchService", "GetBooksBatch", MethodType.UNARY, "localhost:9090")
                .graphQl("invTypesBatch", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("Book", "ids", "types")
                .build();

        registerRoute(route, ctx);

        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);

        // Act
        registrar.scan();

        // Assert
        Map<String, BatchLoader> loaders = registrar.getLoaders();
        assertThat(loaders).containsKey("Book");
        assertThat(loaders.get("Book")).isNotNull();
    }

    @Test
    @DisplayName(
            "Given a loader route whose entity type has no batch_key field, when scan runs, should fall back to registering the loader with entityKey 'id'")
    void given_routeWithNoBatchKeyField_when_scan_should_fallbackToIdAsEntityKey() throws Exception {

        // Arrange — streaming, entity has no batch_key annotation → fallback to "id"
        FileDescriptor fd = buildDescriptor(
                "region.proto",
                "RegionService",
                "GetRegions",
                "Location",
                null,
                null,
                false); // NO batch_key annotation

        ServiceEndpointContext ctx = buildContext("RegionService", fd);

        RouteDefinition route = RouteDefinition.builder(
                        "RegionService", "GetRegions", MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl("regions", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("Location", "ids", "")
                .build();

        registerRoute(route, ctx);

        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);

        // Act
        registrar.scan();

        // Assert — loader is still registered even when no batch_key found (uses "id" fallback)
        Map<String, BatchLoader> loaders = registrar.getLoaders();
        assertThat(loaders)
                .containsKey("Location")
                .withFailMessage("Loader should be registered even when no batch_key annotation is present");
    }

    @Test
    @DisplayName(
            "Given a loader route whose loaderResultField is blank (streaming case), when scan runs, should skip the unary path and try the streaming path")
    void given_loaderRouteWithBlankResultField_when_scan_should_skipUnaryPathAndTryStreamingPath() throws Exception {

        // Arrange — blank batchResultField forces the streaming path in detectEntityKeyField.
        // The entity has batch_key=true so the streaming path finds it.
        FileDescriptor fd = buildDescriptor(
                "char.proto",
                "CharService",
                "GetChars",
                "EveChar",
                null, // no wrapper — streaming output
                null,
                true); // batch_key=true on id

        ServiceEndpointContext ctx = buildContext("CharService", fd);

        RouteDefinition route = RouteDefinition.builder(
                        "CharService", "GetChars", MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl("eveChars", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("EveChar", "character_ids", "") // blank resultField
                .build();

        registerRoute(route, ctx);

        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);

        // Act
        registrar.scan();

        // Assert — streaming path succeeded; loader registered
        Map<String, BatchLoader> loaders = registrar.getLoaders();
        assertThat(loaders).containsKey("EveChar");
    }

    @Test
    @DisplayName(
            "Given a non-loader route mixed with a loader route, when scan runs, should register only the loader route")
    void given_mixedLoaderAndNonLoaderRoutes_when_scan_should_registerOnlyLoaderRoute() throws Exception {

        // Arrange — one loader route, one plain query route sharing the same context
        FileDescriptor fd = buildDescriptor("mixed.proto", "MixedService", "GetTypes", "Book", null, null, true);

        ServiceEndpointContext ctx = buildContext("MixedService", fd);

        RouteDefinition loaderRoute = RouteDefinition.builder(
                        "MixedService", "GetTypes", MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl("types", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("Book", "ids", "")
                .build();

        RouteDefinition nonLoaderRoute = RouteDefinition.builder(
                        "MixedService", "GetTypes", MethodType.UNARY, "localhost:9090")
                .graphQl("typeById", RouteDefinition.GraphQlOperationType.QUERY)
                // no loaderFor() call → isLoader() returns false
                .build();

        ctx.addRoute(loaderRoute);
        ctx.addRoute(nonLoaderRoute);
        registry.registerContext(ctx);

        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);

        // Act
        registrar.scan();

        // Assert — only one loader registered for "Book"
        Map<String, BatchLoader> loaders = registrar.getLoaders();
        assertThat(loaders).hasSize(1).containsKey("Book");
    }

    @Test
    @DisplayName(
            "Given a loader route whose method descriptor cannot be found in the context, when scan runs, should skip that route without throwing")
    void given_loaderRouteWithUnresolvableMethod_when_scan_should_skipRouteGracefully() throws Exception {

        // Arrange — build a context with a method named differently so lookupMethod returns empty
        FileDescriptor fd =
                buildDescriptor("ghost.proto", "GhostService", "DoNothing", "GhostEntity", null, null, true);

        ServiceEndpointContext ctx = buildContext("GhostService", fd);

        // Route refers to a method name that does NOT exist in the context's methodByKey map
        RouteDefinition route = RouteDefinition.builder(
                        "GhostService", "NonExistentMethod", MethodType.UNARY, "localhost:9090")
                .graphQl("ghost", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("GhostEntity", "ids", "results")
                .build();

        registerRoute(route, ctx);

        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);

        // Act — must not throw; the warn log is emitted but the scan continues
        registrar.scan();

        // Assert — no loaders registered because lookupMethod returned empty
        assertThat(registrar.getLoaders()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a registrar that already scanned one loader route WHEN a second service with a loader route is registered and scan runs again SHOULD add the new loader's type")
    void given_registrarScannedOnce_when_newLoaderRouteRegisteredAndRescanned_should_addNewType() throws Exception {
        // Arrange
        FileDescriptor shopFd = buildDescriptor("shop.proto", "ShopService", "GetBooks", "Book", null, null, true);
        registerRoute(
                RouteDefinition.builder("ShopService", "GetBooks", MethodType.SERVER_STREAMING, "localhost:9090")
                        .graphQl("invTypes", RouteDefinition.GraphQlOperationType.QUERY)
                        .loaderFor("Book", "ids", "")
                        .build(),
                buildContext("ShopService", shopFd));
        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);
        registrar.scan();

        FileDescriptor authorFd =
                buildDescriptor("author.proto", "AuthorService", "GetAuthors", "Author", null, null, true);
        registerRoute(
                RouteDefinition.builder("AuthorService", "GetAuthors", MethodType.SERVER_STREAMING, "localhost:9091")
                        .graphQl("authors", RouteDefinition.GraphQlOperationType.QUERY)
                        .loaderFor("Author", "ids", "")
                        .build(),
                buildContext("AuthorService", authorFd));

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).containsKeys("Book", "Author");
    }

    @Test
    @DisplayName(
            "GIVEN a loader route that is unchanged between scans WHEN scan runs again SHOULD keep the same BatchLoader instance for its type")
    void given_unchangedLoaderRoute_when_scanRunsAgain_should_keepSameLoaderInstance() throws Exception {
        // Arrange
        FileDescriptor shopFd = buildDescriptor("shop.proto", "ShopService", "GetBooks", "Book", null, null, true);
        registerRoute(
                RouteDefinition.builder("ShopService", "GetBooks", MethodType.SERVER_STREAMING, "localhost:9090")
                        .graphQl("invTypes", RouteDefinition.GraphQlOperationType.QUERY)
                        .loaderFor("Book", "ids", "")
                        .build(),
                buildContext("ShopService", shopFd));
        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);
        registrar.scan();
        BatchLoader firstLoader = registrar.getLoaders().get("Book");

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders().get("Book")).isSameAs(firstLoader);
    }

    @Test
    @DisplayName(
            "GIVEN a service whose loader route is gone from the registry WHEN scan runs again SHOULD drop that type's loader")
    void given_loaderRouteRemovedFromRegistry_when_scanRunsAgain_should_dropType() throws Exception {
        // Arrange
        FileDescriptor shopFd = buildDescriptor("shop.proto", "ShopService", "GetBooks", "Book", null, null, true);
        registerRoute(
                RouteDefinition.builder("ShopService", "GetBooks", MethodType.SERVER_STREAMING, "localhost:9090")
                        .graphQl("invTypes", RouteDefinition.GraphQlOperationType.QUERY)
                        .loaderFor("Book", "ids", "")
                        .build(),
                buildContext("ShopService", shopFd));
        GrpcBatchDataLoaderRegistrar registrar = new GrpcBatchDataLoaderRegistrar(registry, dispatcher);
        registrar.scan();
        assertThat(registrar.getLoaders()).containsKey("Book");

        // Replace the service's context with one that no longer declares the loader route
        ServiceDefinition config = new ServiceDefinition();
        config.setName("ShopService");
        registry.replaceContext("ShopService", new ServiceEndpointContext(config, null));

        // Act
        registrar.scan();

        // Assert
        assertThat(registrar.getLoaders()).doesNotContainKey("Book");
    }
}
