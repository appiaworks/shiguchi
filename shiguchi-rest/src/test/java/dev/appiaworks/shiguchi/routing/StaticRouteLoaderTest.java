package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.proto.api.AuthzRule;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.MethodRule;
import dev.appiaworks.shiguchi.rest.MergeSpecResolver;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for {@link StaticRouteLoader}.
 *
 * <p>{@code *Grpc} stub classes are hand-written static nested classes exposing a static
 * {@code getServiceDescriptor()} that returns an {@link io.grpc.ServiceDescriptor} whose schema
 * descriptor implements {@link ProtoServiceDescriptorSupplier} — mirroring what protoc-gen-grpc
 * generates, without a real .proto compile step. The underlying proto {@code ServiceDescriptor} is
 * built programmatically via {@code DescriptorProtos} / {@code FileDescriptor.buildFrom}, the same
 * pattern used by {@code GrpcBatchDataLoaderRegistrarTest}.
 *
 * <p>{@link GrpcChannelFactory} and {@link MergeSpecResolver} are mocked (Mockito is already a
 * test dependency of this module); {@link DynamicProtoSerde}, {@link StaticSerdeDetector} and
 * {@link DynamicSerializerFactory} are used as real instances since they are cheap, side-effect
 * free on these tiny descriptors, and exercising them for real is how the existing
 * {@code GrpcBatchDataLoaderRegistrarTest}-style tests in this codebase build contexts.
 */
@DisplayName("StaticRouteLoader")
class StaticRouteLoaderTest {

    // -------------------------------------------------------------------------
    // Fake generated *Grpc stub classes
    // -------------------------------------------------------------------------

    /** Mimics a protoc-gen-grpc outer class: static getServiceDescriptor() + ProtoServiceDescriptorSupplier. */
    public static class GreeterServiceGrpc {
        static com.google.protobuf.Descriptors.ServiceDescriptor PROTO_SERVICE_DESCRIPTOR;

        public static io.grpc.ServiceDescriptor getServiceDescriptor() {
            return io.grpc.ServiceDescriptor.newBuilder(PROTO_SERVICE_DESCRIPTOR.getFullName())
                    .setSchemaDescriptor(new ProtoServiceDescriptorSupplier() {
                        @Override
                        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
                            return PROTO_SERVICE_DESCRIPTOR;
                        }

                        @Override
                        public FileDescriptor getFileDescriptor() {
                            return PROTO_SERVICE_DESCRIPTOR.getFile();
                        }
                    })
                    .build();
        }
    }

    /** Second stub used for a multi-class service definition scenario. */
    public static class AdminServiceGrpc {
        static com.google.protobuf.Descriptors.ServiceDescriptor PROTO_SERVICE_DESCRIPTOR;

        public static io.grpc.ServiceDescriptor getServiceDescriptor() {
            return io.grpc.ServiceDescriptor.newBuilder(PROTO_SERVICE_DESCRIPTOR.getFullName())
                    .setSchemaDescriptor(new ProtoServiceDescriptorSupplier() {
                        @Override
                        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
                            return PROTO_SERVICE_DESCRIPTOR;
                        }

                        @Override
                        public FileDescriptor getFileDescriptor() {
                            return PROTO_SERVICE_DESCRIPTOR.getFile();
                        }
                    })
                    .build();
        }
    }

    /** A "*Grpc" class whose schema descriptor is NOT a ProtoServiceDescriptorSupplier. */
    public static class OpaqueServiceGrpc {
        public static io.grpc.ServiceDescriptor getServiceDescriptor() {
            return io.grpc.ServiceDescriptor.newBuilder("opaque.OpaqueService")
                    .setSchemaDescriptor(new Object())
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Descriptor building helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a FileDescriptor with one service {@code serviceName} exposing one method
     * {@code methodName}, both input and output being an empty "Empty" message.
     */
    private static FileDescriptor buildServiceDescriptor(String fileName, String serviceName, String methodName)
            throws Exception {
        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Empty")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING)
                                .setLabel(Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(serviceName)
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName(methodName)
                                .setInputType(".Empty")
                                .setOutputType(".Empty")
                                .build())
                        .build());

        return FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[0]);
    }

    private static ServiceDefinition staticServiceDef(String name, String... stubClasses) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(name);
        def.setMode(GatewayMode.STATIC);
        def.setHost("localhost");
        def.setPort(9090);
        def.setStaticServiceClasses(new java.util.ArrayList<>(List.of(stubClasses)));
        return def;
    }

    private StaticRouteLoader buildLoader(GatewayProperties props, GrpcRouteRegistry registry) {
        DynamicProtoSerde serde = new DynamicProtoSerde(new GrpcDescriptorCache());
        StaticSerdeDetector tier1 = new StaticSerdeDetector();
        DynamicSerializerFactory tier2 = new DynamicSerializerFactory();
        GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
        when(channelFactory.build(any())).thenReturn(mock(ManagedChannel.class));
        MergeSpecResolver mergeSpecResolver = mock(MergeSpecResolver.class);
        when(mergeSpecResolver.resolve(any(), any())).thenReturn(Optional.empty());

        return new StaticRouteLoader(
                props,
                registry,
                Optional.empty(),
                serde,
                tier1,
                tier2,
                channelFactory,
                new ClasspathServiceScanner(),
                mock(SpringBeanServiceDiscovery.class),
                mergeSpecResolver);
    }

    private GatewayProperties propsWith(ServiceDefinition... services) {
        GatewayProperties props = new GatewayProperties();
        props.setServices(List.of(services));
        return props;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "GIVEN a STATIC service with one valid stub class WHEN buildContext SHOULD register a route with a gRPC channel and the resolved FQN")
    void given_staticServiceWithOneStub_when_buildContext_should_registerGrpcRoute() throws Exception {
        // Arrange
        FileDescriptor fd = buildServiceDescriptor("greeter.proto", "GreeterService", "SayHello");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("GreeterService");

        ServiceDefinition svcDef = staticServiceDef("greeter", GreeterServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getChannel()).isNotNull();
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getFullServiceName()).isEqualTo("GreeterService");
        assertThat(ctx.getRoutes().get(0).getMethodName()).isEqualTo("SayHello");
        assertThat(ctx.getServiceByFqn()).containsKey("GreeterService");
    }

    @Test
    @DisplayName(
            "GIVEN a STATIC service with no static-service-classes configured WHEN buildContext SHOULD return a context with no routes")
    void given_staticServiceWithNoStubClasses_when_buildContext_should_returnEmptyContext() throws Exception {
        // Arrange
        ServiceDefinition svcDef = staticServiceDef("empty-svc"); // no stub classes
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).isEmpty();
        assertThat(ctx.getServiceByFqn()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a stub class name that does not exist on the classpath WHEN buildContext SHOULD skip it without throwing and register no route for it")
    void given_stubClassNotFoundOnClasspath_when_buildContext_should_skipGracefully() throws Exception {
        // Arrange
        ServiceDefinition svcDef = staticServiceDef("ghost", "com.example.DoesNotExistGrpc");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act — must not throw
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a stub class whose schema descriptor is not a ProtoServiceDescriptorSupplier WHEN buildContext SHOULD skip it without throwing")
    void given_stubClassWithNonProtoSchemaDescriptor_when_buildContext_should_skipGracefully() throws Exception {
        // Arrange
        ServiceDefinition svcDef = staticServiceDef("opaque", OpaqueServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act — must not throw
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a service with two stub classes WHEN buildContext SHOULD register routes for both services")
    void given_serviceWithTwoStubClasses_when_buildContext_should_registerRoutesForBoth() throws Exception {
        // Arrange
        FileDescriptor greeterFd = buildServiceDescriptor("greeter2.proto", "GreeterService2", "SayHello");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = greeterFd.findServiceByName("GreeterService2");
        FileDescriptor adminFd = buildServiceDescriptor("admin.proto", "AdminService", "Reset");
        AdminServiceGrpc.PROTO_SERVICE_DESCRIPTOR = adminFd.findServiceByName("AdminService");

        ServiceDefinition svcDef =
                staticServiceDef("multi", GreeterServiceGrpc.class.getName(), AdminServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(2);
        assertThat(ctx.getServiceByFqn()).containsKeys("GreeterService2", "AdminService");
    }

    @Test
    @DisplayName(
            "GIVEN a method excluded via excludeMethods WHEN buildContext SHOULD not register a route for that method")
    void given_methodExcludedByFilter_when_buildContext_should_notRegisterRoute() throws Exception {
        // Arrange
        FileDescriptor fd = buildServiceDescriptor("filtered.proto", "FilteredService", "Debug");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("FilteredService");

        ServiceDefinition svcDef = staticServiceDef("filtered", GreeterServiceGrpc.class.getName());
        svcDef.setExcludeMethods(List.of("Debug"));
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert — method excluded, so no route (not even a bare GraphQL/REST-less one) is added
        assertThat(ctx.getRoutes()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a method with no explicit shiguchi.api.graphql annotation WHEN buildContext SHOULD fall back to the servicePrefix+MethodName GraphQL field naming convention")
    void given_methodWithoutGqlSchemaAnnotation_when_buildContext_should_useConventionBasedGraphQlName()
            throws Exception {
        // Arrange
        FileDescriptor fd = buildServiceDescriptor("shop.proto", "ShopCategoryService", "GetById");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("ShopCategoryService");

        ServiceDefinition svcDef = staticServiceDef("inv", GreeterServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getGraphQlName()).isEqualTo("shopCategoryGetById");
        assertThat(ctx.getRoutes().get(0).getGraphQlOperationType())
                .isEqualTo(RouteDefinition.GraphQlOperationType.QUERY);
    }

    @Test
    @DisplayName(
            "GIVEN rest-enabled is false globally WHEN buildContext SHOULD register routes without an HTTP binding")
    void given_restDisabledGlobally_when_buildContext_should_registerRoutesWithoutHttpBinding() throws Exception {
        // Arrange
        FileDescriptor fd = buildServiceDescriptor("norest.proto", "NoRestService", "DoThing");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("NoRestService");

        ServiceDefinition svcDef = staticServiceDef("norest", GreeterServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        props.getRest().setEnabled(false);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).hasRestBinding()).isFalse();
        assertThat(ctx.getRoutes().get(0).hasGraphQlBinding()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN graphQlFieldName called with a service ending in 'Service' and a method 'GetById' SHOULD strip the suffix and lower-camel-case the prefix")
    void given_serviceNameEndingInService_when_graphQlFieldNameCalled_should_stripSuffixAndLowerCamelCase() {
        // Act
        String result = StaticRouteLoader.graphQlFieldName("ShopCategoryService", "GetById");

        // Assert
        assertThat(result).isEqualTo("shopCategoryGetById");
    }

    @Test
    @DisplayName(
            "GIVEN load() runs across all configured services WHEN some are STATIC and some are not SHOULD register a context only for the STATIC ones")
    void given_mixOfStaticAndNonStaticServices_when_load_should_registerOnlyStaticContexts() throws Exception {
        // Arrange
        FileDescriptor fd = buildServiceDescriptor("mixmode.proto", "MixModeService", "Ping");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("MixModeService");

        ServiceDefinition staticSvc = staticServiceDef("static-svc", GreeterServiceGrpc.class.getName());
        ServiceDefinition dynamicSvc = new ServiceDefinition();
        dynamicSvc.setName("dynamic-svc");
        dynamicSvc.setMode(GatewayMode.DYNAMIC);

        GatewayProperties props = propsWith(staticSvc, dynamicSvc);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        loader.load();

        // Assert — only the STATIC service was loaded by this loader
        assertThat(registry.contextByServiceName("static-svc")).isPresent();
        assertThat(registry.contextByServiceName("dynamic-svc")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a STATIC service whose buildContext throws during applyRetryExclusions "
            + "WHEN buildContext is called SHOULD throw AND the channel SHOULD be shut down")
    void given_staticServiceThatThrowsDuringBuild_when_buildContext_should_throwAndShutdownChannel() throws Exception {
        // Arrange: Set up a valid stub so the loader gets far enough to build a channel.
        FileDescriptor fd = buildServiceDescriptor("failsafe.proto", "FailSafeService", "Ping");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("FailSafeService");

        ServiceDefinition svcDef = staticServiceDef("failsafe-svc", GreeterServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();

        // Create a mock ManagedChannel that we can verify was shut down.
        ManagedChannel mockChannel = mock(ManagedChannel.class);

        // Build components manually to inject a custom channelFactory that throws during applyRetryExclusions.
        DynamicProtoSerde serde = new DynamicProtoSerde(new GrpcDescriptorCache());
        StaticSerdeDetector tier1 = new StaticSerdeDetector();
        DynamicSerializerFactory tier2 = new DynamicSerializerFactory();

        GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
        when(channelFactory.build(any())).thenReturn(mockChannel);
        // Configure applyRetryExclusions to throw, which will happen during buildContext(svcDef, channel)
        org.mockito.Mockito.doThrow(new RuntimeException("Simulated applyRetryExclusions failure"))
                .when(channelFactory)
                .applyRetryExclusions(any(), any());

        MergeSpecResolver mergeSpecResolver = mock(MergeSpecResolver.class);
        when(mergeSpecResolver.resolve(any(), any())).thenReturn(Optional.empty());

        StaticRouteLoader loader = new StaticRouteLoader(
                props,
                registry,
                Optional.empty(),
                serde,
                tier1,
                tier2,
                channelFactory,
                new ClasspathServiceScanner(),
                mock(SpringBeanServiceDiscovery.class),
                mergeSpecResolver);

        // Act & Assert: buildContext must throw
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.buildContext(svcDef))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated applyRetryExclusions failure");

        // Assert: the channel MUST be shut down (not left hanging), even though buildContext failed
        verify(mockChannel).shutdownNow();
    }

    /**
     * Helper to build a descriptor with one service and three methods of different streaming
     * types: bidi, client-streaming, server-streaming. Each has an HTTP annotation.
     */
    private static FileDescriptor buildBidiTestDescriptor(String fileName, String serviceName) throws Exception {
        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Empty")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING)
                                .setLabel(Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(serviceName)
                        // Bidirectional streaming method with HTTP POST
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Chat")
                                .setInputType(".Empty")
                                .setOutputType(".Empty")
                                .setClientStreaming(true)
                                .setServerStreaming(true)
                                .setOptions(com.google.protobuf.DescriptorProtos.MethodOptions.newBuilder()
                                        .setExtension(
                                                com.google.api.AnnotationsProto.http,
                                                com.google.api.HttpRule.newBuilder()
                                                        .setPost("/v1/chat")
                                                        .build())
                                        .build())
                                .build())
                        // Client-streaming method with HTTP POST
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Upload")
                                .setInputType(".Empty")
                                .setOutputType(".Empty")
                                .setClientStreaming(true)
                                .setServerStreaming(false)
                                .setOptions(com.google.protobuf.DescriptorProtos.MethodOptions.newBuilder()
                                        .setExtension(
                                                com.google.api.AnnotationsProto.http,
                                                com.google.api.HttpRule.newBuilder()
                                                        .setPost("/v1/upload")
                                                        .build())
                                        .build())
                                .build())
                        // Server-streaming method with HTTP GET
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Feed")
                                .setInputType(".Empty")
                                .setOutputType(".Empty")
                                .setClientStreaming(false)
                                .setServerStreaming(true)
                                .setOptions(com.google.protobuf.DescriptorProtos.MethodOptions.newBuilder()
                                        .setExtension(
                                                com.google.api.AnnotationsProto.http,
                                                com.google.api.HttpRule.newBuilder()
                                                        .setGet("/v1/feed")
                                                        .build())
                                        .build())
                                .build())
                        .build());

        return FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[0]);
    }

    @Test
    @DisplayName("GIVEN a bidirectional streaming method annotated with google.api.http POST rule "
            + "WHEN the service is loaded SHOULD not register a route with REST binding for that method")
    void given_bidiMethodAnnotatedWithHttpRule_when_buildContext_should_notRegisterRestBinding() throws Exception {
        // Arrange
        FileDescriptor fd = buildBidiTestDescriptor("bidi.proto", "BidiService");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("BidiService");

        ServiceDefinition svcDef = staticServiceDef("bidi-svc", GreeterServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        props.getGraphql().setEnabled(false); // Disable GraphQL for simplicity
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(2); // Only Upload and Feed, not Chat
        assertThat(ctx.getRoutes().stream()
                        .filter(r -> r.getMethodName().equals("Chat"))
                        .collect(java.util.stream.Collectors.toList()))
                .isEmpty(); // Chat should not be registered at all (no REST, no GraphQL)
        assertThat(ctx.getRoutes().stream()
                        .map(RouteDefinition::getMethodName)
                        .collect(java.util.stream.Collectors.toList()))
                .containsExactlyInAnyOrder("Upload", "Feed");
    }

    @Test
    @DisplayName("GIVEN a service with bidi, client-streaming, and server-streaming methods annotated with HTTP rules "
            + "WHEN the service is loaded SHOULD still register REST routes for the non-bidi methods")
    void given_mixedStreamingMethodsAnnotatedWithHttp_when_buildContext_should_registerNonBidiRestRoutes()
            throws Exception {
        // Arrange
        FileDescriptor fd = buildBidiTestDescriptor("mixed-stream.proto", "MixedStreamService");
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("MixedStreamService");

        ServiceDefinition svcDef = staticServiceDef("mixed-stream-svc", GreeterServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        props.getGraphql().setEnabled(false); // Disable GraphQL for simplicity
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        StaticRouteLoader loader = buildLoader(props, registry);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert — Upload and Feed should have REST bindings, Chat should not
        assertThat(ctx.getRoutes()).hasSize(2);
        RouteDefinition uploadRoute = ctx.getRoutes().stream()
                .filter(r -> r.getMethodName().equals("Upload"))
                .findFirst()
                .orElseThrow();
        RouteDefinition feedRoute = ctx.getRoutes().stream()
                .filter(r -> r.getMethodName().equals("Feed"))
                .findFirst()
                .orElseThrow();

        assertThat(uploadRoute.hasRestBinding()).isTrue();
        assertThat(uploadRoute.getHttpPath()).isEqualTo("/v1/upload");
        assertThat(uploadRoute.getHttpMethod()).isEqualTo("POST");

        assertThat(feedRoute.hasRestBinding()).isTrue();
        assertThat(feedRoute.getHttpPath()).isEqualTo("/v1/feed");
        assertThat(feedRoute.getHttpMethod()).isEqualTo("GET");
    }

    // -------------------------------------------------------------------------
    // (shiguchi.api.method) access rules on the REST surface
    // -------------------------------------------------------------------------

    /** One service, one GET-bound method carrying the given {@code (shiguchi.api.method)} annotation. */
    private static FileDescriptor buildAnnotatedServiceDescriptor(
            String fileName, String serviceName, String methodName, MethodRule schema) throws Exception {
        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Empty")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING)
                                .setLabel(Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(serviceName)
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName(methodName)
                                .setInputType(".Empty")
                                .setOutputType(".Empty")
                                .setOptions(MethodOptions.newBuilder()
                                        .setExtension(
                                                AnnotationsProto.http,
                                                HttpRule.newBuilder()
                                                        .setGet("/v1/docs/{id}")
                                                        .build())
                                        .setExtension(MethodProto.method, schema)
                                        .build())
                                .build())
                        .build());

        return FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[0]);
    }

    @Test
    @DisplayName("GIVEN a method declaring auth_scopes and authz AND GraphQL disabled globally "
            + "WHEN buildContext SHOULD still put the scopes and authz rules on the REST route")
    void GIVEN_authScopesAndGraphQlDisabled_WHEN_buildContext_SHOULD_keepAccessRulesOnRestRoute() throws Exception {
        // Arrange
        MethodRule schema = MethodRule.newBuilder()
                .addAuthScopes("docs.read")
                .addAuthz(AuthzRule.newBuilder()
                        .setObjectType("document")
                        .setRelation("viewer")
                        .setIdField("id"))
                .build();
        FileDescriptor fd = buildAnnotatedServiceDescriptor("docs.proto", "GreeterService", "GetDoc", schema);
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("GreeterService");

        ServiceDefinition svcDef = staticServiceDef("docs", GreeterServiceGrpc.class.getName());
        GatewayProperties props = propsWith(svcDef);
        props.getGraphql().setEnabled(false);
        StaticRouteLoader loader = buildLoader(props, new GrpcRouteRegistry());

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(1);
        RouteDefinition route = ctx.getRoutes().get(0);
        assertThat(route.hasRestBinding()).isTrue();
        assertThat(route.hasGraphQlBinding()).isFalse();
        assertThat(route.getAuthScopes()).containsExactly("docs.read");
        assertThat(route.getAuthzRules()).containsExactly(new AuthzRuleDefinition("document", "viewer", "id"));
    }

    @Test
    @DisplayName("GIVEN a (shiguchi.api.method) annotation with auth_scopes and no GraphQL option "
            + "WHEN buildContext SHOULD put the scopes on the route")
    void GIVEN_authScopesWithoutFieldName_WHEN_buildContext_SHOULD_keepScopesOnRoute() throws Exception {
        // Arrange
        MethodRule schema = MethodRule.newBuilder().addAuthScopes("docs.read").build();
        FileDescriptor fd = buildAnnotatedServiceDescriptor("docs-noname.proto", "GreeterService", "GetDoc", schema);
        GreeterServiceGrpc.PROTO_SERVICE_DESCRIPTOR = fd.findServiceByName("GreeterService");

        ServiceDefinition svcDef = staticServiceDef("docs", GreeterServiceGrpc.class.getName());
        StaticRouteLoader loader = buildLoader(propsWith(svcDef), new GrpcRouteRegistry());

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getAuthScopes()).containsExactly("docs.read");
    }
}
