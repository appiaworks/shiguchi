package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.merge.MergeSource;
import dev.appiaworks.shiguchi.merge.MergeSpec;
import dev.appiaworks.shiguchi.proto.api.AuthzRule;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.MethodRule;
import dev.appiaworks.shiguchi.rest.MergeSpecResolver;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BDD-style unit tests for {@link DynamicRouteLoader}.
 *
 * <p>Covers what {@code StaticRouteLoaderTest} and {@code RestDownstreamRouteLoaderTest} do for
 * their loaders: DYNAMIC mode discovery via a real in-process gRPC server running
 * {@link ProtoReflectionServiceV1} (mirroring {@code GrpcReflectionClientTest}'s pattern), PB_FILE
 * mode from a descriptor-set file, include/exclude-methods filtering, the deferred-retry /
 * reload-listener contract already proven for the sibling loaders, merge-spec attachment, context
 * replacement, and the {@code ScgRouteRegistrar} being optional.
 *
 * <p>{@link GrpcChannelFactory} is mocked to hand back the loader's own in-process
 * {@link ManagedChannel} (DYNAMIC) or a bare mock (PB_FILE, which never dials out); real
 * {@link DynamicProtoSerde}, {@link StaticSerdeDetector} and {@link DynamicSerializerFactory}
 * instances are used, matching {@code StaticRouteLoaderTest}'s stated rationale. {@link MergeSpecResolver}
 * is mocked since exercising the real proto-annotation path belongs to that class's own tests.
 */
@DisplayName("DynamicRouteLoader")
class DynamicRouteLoaderTest {

    private final List<Server> servers = new java.util.ArrayList<>();
    private final List<ManagedChannel> channels = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        channels.forEach(ManagedChannel::shutdownNow);
        servers.forEach(Server::shutdownNow);
    }

    // -------------------------------------------------------------------------
    // Descriptor building helpers
    // -------------------------------------------------------------------------

    /**
     * One service, one method, an optional google.api.http GET rule on the method.
     *
     * @param serviceFqn fully-qualified proto service name, e.g. {@code dev.example.GreeterService}.
     *     The file descriptor's package is derived from it, so the resulting service's
     *     {@code getFullName()} — what gRPC reflection lists and what {@code DynamicRouteLoader}
     *     matches against — equals {@code serviceFqn} exactly.
     */
    private static FileDescriptor buildFileDescriptor(
            String fileName, String serviceFqn, String methodName, String httpGetPath) throws Exception {
        DescriptorProto empty = DescriptorProto.newBuilder()
                .setName("Empty")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        String emptyType = "." + qualifiedTypeName(serviceFqn, "Empty");
        MethodDescriptorProto.Builder method = MethodDescriptorProto.newBuilder()
                .setName(methodName)
                .setInputType(emptyType)
                .setOutputType(emptyType);
        if (httpGetPath != null) {
            method.setOptions(MethodOptions.newBuilder()
                    .setExtension(
                            AnnotationsProto.http,
                            HttpRule.newBuilder().setGet(httpGetPath).build()));
        }

        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addMessageType(empty)
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(simpleName(serviceFqn))
                        .addMethod(method)
                        .build());
        applyPackage(fileProto, serviceFqn);

        return FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[0]);
    }

    private static FileDescriptor buildTwoMethodFileDescriptor(
            String fileName, String serviceFqn, String method1, String method2) throws Exception {
        DescriptorProto empty = DescriptorProto.newBuilder()
                .setName("Empty")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        String emptyType = "." + qualifiedTypeName(serviceFqn, "Empty");
        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addMessageType(empty)
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(simpleName(serviceFqn))
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName(method1)
                                .setInputType(emptyType)
                                .setOutputType(emptyType)
                                .build())
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName(method2)
                                .setInputType(emptyType)
                                .setOutputType(emptyType)
                                .build())
                        .build());
        applyPackage(fileProto, serviceFqn);

        return FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[0]);
    }

    private static void applyPackage(FileDescriptorProto.Builder fileProto, String serviceFqn) {
        int lastDot = serviceFqn.lastIndexOf('.');
        if (lastDot >= 0) {
            fileProto.setPackage(serviceFqn.substring(0, lastDot));
        }
    }

    private static String simpleName(String serviceFqn) {
        int lastDot = serviceFqn.lastIndexOf('.');
        return lastDot == -1 ? serviceFqn : serviceFqn.substring(lastDot + 1);
    }

    /** {@code "pkg.Name"} when {@code serviceFqn} has a package, else just {@code "Name"}. */
    private static String qualifiedTypeName(String serviceFqn, String typeName) {
        int lastDot = serviceFqn.lastIndexOf('.');
        return lastDot == -1 ? typeName : serviceFqn.substring(0, lastDot) + "." + typeName;
    }

    /** Starts an in-process server exposing one gRPC service plus real server reflection. */
    private ManagedChannel startReflectionServer(String serviceFqn, FileDescriptor fileDescriptor) throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        var serviceDescriptor = fileDescriptor.findServiceByName(extractServiceName(serviceFqn));

        var schema = new TestProtoDescriptorSupplier(fileDescriptor, serviceDescriptor);
        var descriptorBuilder = io.grpc.ServiceDescriptor.newBuilder(serviceFqn).setSchemaDescriptor(schema);
        for (com.google.protobuf.Descriptors.MethodDescriptor md : serviceDescriptor.getMethods()) {
            descriptorBuilder.addMethod(dummyMethod(serviceFqn, md.getName()));
        }
        var descriptor = descriptorBuilder.build();

        var serviceBuilder = ServerServiceDefinition.builder(descriptor);
        for (io.grpc.MethodDescriptor<?, ?> m : descriptor.getMethods()) {
            @SuppressWarnings("unchecked")
            io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> castMethod =
                    (io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>) m;
            serviceBuilder.addMethod(castMethod, ServerCalls.asyncUnaryCall((req, obs) -> {
                obs.onNext(req);
                obs.onCompleted();
            }));
        }

        Server startedServer = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(serviceBuilder.build())
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        servers.add(startedServer);

        ManagedChannel newChannel =
                InProcessChannelBuilder.forName(serverName).directExecutor().build();
        channels.add(newChannel);
        return newChannel;
    }

    private static MethodDescriptor<DynamicMessage, DynamicMessage> dummyMethod(String serviceFqn, String methodName)
            throws Exception {
        FileDescriptor dummyFd = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("dummy_" + serviceFqn.replace('.', '_') + "_" + methodName + ".proto")
                        .setSyntax("proto3")
                        .addMessageType(DescriptorProto.newBuilder().setName("Empty"))
                        .build(),
                new FileDescriptor[0]);
        var empty = dummyFd.findMessageTypeByName("Empty");
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(serviceFqn + "/" + methodName)
                .setRequestMarshaller(
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(empty).build()))
                .setResponseMarshaller(
                        ProtoUtils.marshaller(DynamicMessage.newBuilder(empty).build()))
                .build();
    }

    private static String extractServiceName(String serviceFqn) {
        int lastDot = serviceFqn.lastIndexOf('.');
        return lastDot == -1 ? serviceFqn : serviceFqn.substring(lastDot + 1);
    }

    private static class TestProtoDescriptorSupplier
            implements ProtoFileDescriptorSupplier, ProtoServiceDescriptorSupplier {
        private final FileDescriptor fileDescriptor;
        private final com.google.protobuf.Descriptors.ServiceDescriptor serviceDescriptor;

        TestProtoDescriptorSupplier(
                FileDescriptor fileDescriptor, com.google.protobuf.Descriptors.ServiceDescriptor serviceDescriptor) {
            this.fileDescriptor = fileDescriptor;
            this.serviceDescriptor = serviceDescriptor;
        }

        @Override
        public FileDescriptor getFileDescriptor() {
            return fileDescriptor;
        }

        @Override
        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
            return serviceDescriptor;
        }
    }

    // -------------------------------------------------------------------------
    // Service definition / property / loader wiring helpers
    // -------------------------------------------------------------------------

    private static ServiceDefinition dynamicServiceDef(String name) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(name);
        def.setMode(GatewayMode.DYNAMIC);
        def.setHost("localhost");
        def.setPort(9090);
        return def;
    }

    private static ServiceDefinition pbFileServiceDef(String name, String pbPath) {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(name);
        def.setMode(GatewayMode.PB_FILE);
        def.setPbDescriptorPath(pbPath);
        def.setHost("localhost");
        def.setPort(9090);
        return def;
    }

    private static GatewayProperties propsWith(ServiceDefinition... services) {
        GatewayProperties props = new GatewayProperties();
        props.setServices(List.of(services));
        return props;
    }

    private DynamicRouteLoader buildLoader(
            GatewayProperties props,
            GrpcRouteRegistry registry,
            ManagedChannel channelToReturn,
            Optional<ScgRouteRegistrar> scgRegistrar,
            MergeSpecResolver mergeSpecResolver) {
        DynamicProtoSerde serde = new DynamicProtoSerde(new GrpcDescriptorCache());
        StaticSerdeDetector tier1 = new StaticSerdeDetector();
        DynamicSerializerFactory tier2 = new DynamicSerializerFactory();
        GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
        when(channelFactory.build(any())).thenReturn(channelToReturn);

        return new DynamicRouteLoader(
                props, registry, scgRegistrar, serde, tier1, tier2, channelFactory, mergeSpecResolver);
    }

    private MergeSpecResolver noopMergeResolver() {
        MergeSpecResolver resolver = mock(MergeSpecResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());
        return resolver;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GIVEN a DYNAMIC service backed by a real in-process reflection server "
            + "WHEN buildContext SHOULD discover the service via reflection and register its HTTP-bound route")
    void given_dynamicServiceWithReflectionServer_when_buildContext_should_discoverServiceAndRegisterRoute()
            throws Exception {
        // Arrange
        FileDescriptor fd =
                buildFileDescriptor("greeter.proto", "dev.example.GreeterService", "SayHello", "/v1/hello/{id}");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.GreeterService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("greeter");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, inProcessChannel, Optional.empty(), noopMergeResolver());

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getServiceByFqn()).containsKey("dev.example.GreeterService");
        assertThat(ctx.getRoutes()).hasSize(1);
        RouteDefinition route = ctx.getRoutes().get(0);
        assertThat(route.getFullServiceName()).isEqualTo("dev.example.GreeterService");
        assertThat(route.getMethodName()).isEqualTo("SayHello");
        assertThat(route.getHttpPath()).isEqualTo("/v1/hello/{id}");
        assertThat(route.getHttpMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("GIVEN a PB_FILE service pointing at a descriptor-set fixture "
            + "WHEN buildContext SHOULD load descriptors from the file without any gRPC reflection call")
    void given_pbFileServiceWithDescriptorSetFixture_when_buildContext_should_loadFromFileNotReflection(
            @TempDir Path tempDir) throws Exception {
        // Arrange
        FileDescriptor fd = buildFileDescriptor("pbfile.proto", "dev.example.PbFileService", "DoThing", "/v1/thing");
        FileDescriptorSet fds =
                FileDescriptorSet.newBuilder().addFile(fd.toProto()).build();
        Path pbFile = tempDir.resolve("pbfile.pb");
        Files.write(pbFile, fds.toByteArray());

        ServiceDefinition svcDef = pbFileServiceDef("pbfile-svc", pbFile.toString());
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        // No reflection server started at all — a channel mock proves reflection is never invoked.
        DynamicRouteLoader loader =
                buildLoader(props, registry, mock(ManagedChannel.class), Optional.empty(), noopMergeResolver());

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getServiceByFqn()).containsKey("dev.example.PbFileService");
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getMethodName()).isEqualTo("DoThing");
        assertThat(ctx.getRoutes().get(0).getHttpPath()).isEqualTo("/v1/thing");
    }

    @Test
    @DisplayName(
            "GIVEN PB_FILE mode with a blank pbDescriptorPath WHEN buildContext SHOULD throw IllegalStateException naming the service")
    void given_pbFileModeWithBlankPath_when_buildContext_should_throwIllegalState() {
        // Arrange
        ServiceDefinition svcDef = pbFileServiceDef("bad-pbfile", "");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, mock(ManagedChannel.class), Optional.empty(), noopMergeResolver());

        // Act & Assert
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.buildContext(svcDef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bad-pbfile")
                .hasMessageContaining("PB_FILE");
    }

    @Test
    @DisplayName("GIVEN a service with two methods and excludeMethods filtering one out "
            + "WHEN buildContext SHOULD register a route only for the method that passes the filter")
    void given_excludeMethodsFilter_when_buildContext_should_registerOnlyAllowedMethod() throws Exception {
        // Arrange
        FileDescriptor fd =
                buildTwoMethodFileDescriptor("filtered.proto", "dev.example.FilteredService", "Debug", "List");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.FilteredService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("filtered");
        svcDef.setExcludeMethods(List.of("Debug"));
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, inProcessChannel, Optional.empty(), noopMergeResolver());

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert — "Debug" is filtered out entirely (no route at all); "List" still gets a route
        // (a convention-based GraphQL binding, since GraphQL is enabled by default).
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getMethodName()).isEqualTo("List");
    }

    @Test
    @DisplayName("GIVEN a service with includeMethods restricting to one method "
            + "WHEN buildContext SHOULD not register a route for the method left out of the include list")
    void given_includeMethodsFilter_when_buildContext_should_excludeMethodNotIncluded() throws Exception {
        // Arrange
        FileDescriptor fd = buildTwoMethodFileDescriptor("incl.proto", "dev.example.InclService", "Keep", "Drop");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.InclService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("incl-svc");
        svcDef.setIncludeMethods(List.of("Keep"));
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, inProcessChannel, Optional.empty(), noopMergeResolver());

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getMethodName()).isEqualTo("Keep");
    }

    @Test
    @DisplayName(
            "GIVEN a backend that is down at startup WHEN load runs SHOULD defer it silently, "
                    + "and a subsequent successful retryFailedOnReady SHOULD register it and notify reload listeners exactly once")
    void given_backendDownAtStartup_when_loadThenRetrySucceeds_should_registerAndNotifyOnce() throws Exception {
        // Arrange: the same ServiceDefinition/loader is used throughout — only what
        // GrpcChannelFactory.build() hands back changes between the first (failing) attempt and
        // the retry, mirroring the real self-connection race the loader's javadoc describes: the
        // backend starts listening between @PostConstruct and ApplicationReadyEvent.
        FileDescriptor fd = buildFileDescriptor("late.proto", "dev.example.LateService", "Ping", null);
        ManagedChannel deadChannel = InProcessChannelBuilder.forName("nonexistent-" + System.nanoTime())
                .directExecutor()
                .build();
        channels.add(deadChannel);

        ServiceDefinition svcDef = dynamicServiceDef("late-svc");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();

        DynamicProtoSerde serde = new DynamicProtoSerde(new GrpcDescriptorCache());
        StaticSerdeDetector tier1 = new StaticSerdeDetector();
        DynamicSerializerFactory tier2 = new DynamicSerializerFactory();
        GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
        // First call (load()) returns a channel to nothing listening; second call (the retry)
        // returns a channel to the now-started reflection server.
        when(channelFactory.build(any()))
                .thenReturn(deadChannel)
                .thenAnswer(inv -> startReflectionServer("dev.example.LateService", fd));

        DynamicRouteLoader loader = new DynamicRouteLoader(
                props, registry, Optional.empty(), serde, tier1, tier2, channelFactory, noopMergeResolver());
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        loader.setReloadListeners(() -> List.of(reloads::incrementAndGet));

        // Act 1: load() fails silently because the channel cannot reach a reflection service.
        loader.load();

        // Assert 1: nothing registered, no notification.
        assertThat(registry.allContexts()).isEmpty();
        assertThat(reloads.get()).isZero();

        // Act 2: ApplicationReadyEvent fires; this time the backend is reachable.
        loader.retryFailedOnReady();

        // Assert 2: registered exactly once, notified exactly once.
        assertThat(registry.contextByServiceName("late-svc")).isPresent();
        assertThat(reloads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN retryFailedOnReady runs but every deferred service still fails "
            + "WHEN called SHOULD clear the pending list without registering anything or notifying listeners")
    void given_retryStillFails_when_retryFailedOnReady_should_notNotifyListeners() throws Exception {
        // Arrange
        ServiceDefinition svcDef = dynamicServiceDef("still-down");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        ManagedChannel deadChannel = InProcessChannelBuilder.forName("nonexistent-" + System.nanoTime())
                .directExecutor()
                .build();
        DynamicRouteLoader loader = buildLoader(props, registry, deadChannel, Optional.empty(), noopMergeResolver());
        java.util.concurrent.atomic.AtomicInteger reloads = new java.util.concurrent.atomic.AtomicInteger();
        loader.setReloadListeners(() -> List.of(reloads::incrementAndGet));

        // Act
        loader.load();
        loader.retryFailedOnReady();

        // Assert
        assertThat(registry.allContexts()).isEmpty();
        assertThat(reloads.get()).isZero();

        // A further retryFailedOnReady is a no-op since the pending list was cleared.
        loader.retryFailedOnReady();
        assertThat(reloads.get()).isZero();
        deadChannel.shutdownNow();
    }

    @Test
    @DisplayName("GIVEN MergeSpecResolver resolves a MergeSpec for a route "
            + "WHEN buildContext SHOULD attach that merge spec to the registered route")
    void given_mergeSpecResolverResolvesSpec_when_buildContext_should_attachMergeSpecToRoute() throws Exception {
        // Arrange
        FileDescriptor fd = buildFileDescriptor("merged.proto", "dev.example.MergedService", "List", "/v1/merged");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.MergedService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("merged-svc");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();

        MergeSpec spec = new MergeSpec(
                List.of(new MergeSource("a", "svc.A", "List", true), new MergeSource("b", "svc.B", "List", true)),
                "id",
                MergeSpec.TotalCountStrategy.UNKNOWN,
                MergeSpec.FailurePolicy.FAIL_FAST,
                0);
        MergeSpecResolver mergeSpecResolver = mock(MergeSpecResolver.class);
        when(mergeSpecResolver.resolve(any(), any())).thenReturn(Optional.of(spec));

        DynamicRouteLoader loader = buildLoader(props, registry, inProcessChannel, Optional.empty(), mergeSpecResolver);

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getMergeSpec()).isEqualTo(spec);
    }

    @Test
    @DisplayName("GIVEN ScgRouteRegistrar is absent (Optional.empty) "
            + "WHEN load runs SHOULD still register the context into GrpcRouteRegistry without error")
    void given_scgRegistrarAbsent_when_load_should_stillRegisterIntoRouteRegistry() throws Exception {
        // Arrange
        FileDescriptor fd = buildFileDescriptor("noscg.proto", "dev.example.NoScgService", "Ping", "/v1/ping");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.NoScgService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("no-scg");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, inProcessChannel, Optional.empty(), noopMergeResolver());

        // Act — must not throw despite no ScgRouteRegistrar bean being present
        loader.load();

        // Assert
        assertThat(registry.contextByServiceName("no-scg")).isPresent();
        assertThat(registry.contextByServiceName("no-scg").get().getRoutes()).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN ScgRouteRegistrar is present WHEN load registers a context "
            + "SHOULD call register on it exactly once with that context")
    void given_scgRegistrarPresent_when_load_should_registerContextWithScgRegistrar() throws Exception {
        // Arrange
        FileDescriptor fd = buildFileDescriptor("withscg.proto", "dev.example.WithScgService", "Ping", "/v1/ping2");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.WithScgService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("with-scg");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        ScgRouteRegistrar scgRegistrar = mock(ScgRouteRegistrar.class);
        DynamicRouteLoader loader =
                buildLoader(props, registry, inProcessChannel, Optional.of(scgRegistrar), noopMergeResolver());

        // Act
        loader.load();

        // Assert
        verify(scgRegistrar, times(1)).register(any(ServiceEndpointContext.class));
    }

    @Test
    @DisplayName("GIVEN a freshly built context for an already-registered service "
            + "WHEN the registry's replaceContext is called SHOULD swap routes wholesale rather than merge them")
    void given_replaceContext_when_called_should_swapRoutesWholesaleNotMerge() throws Exception {
        // Arrange: build an initial context with one route, register it, then build a second
        // context (as a reload would) with a different route set and replace.
        FileDescriptor fdV1 = buildFileDescriptor("swap.proto", "dev.example.SwapService", "MethodV1", "/v1/swap/v1");
        ManagedChannel channelV1 = startReflectionServer("dev.example.SwapService", fdV1);

        ServiceDefinition svcDef = dynamicServiceDef("swap-svc");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loaderV1 = buildLoader(props, registry, channelV1, Optional.empty(), noopMergeResolver());
        ServiceEndpointContext ctxV1 = loaderV1.buildContext(svcDef);
        registry.registerContext(ctxV1);
        assertThat(registry.contextByServiceName("swap-svc").get().getRoutes()).hasSize(1);
        assertThat(registry.findByHttpKey("GET", "/v1/swap/v1")).isPresent();

        // Stand up a second in-process server/channel (v1's are torn down in @AfterEach) with a
        // totally different route, as a reload's rebuilt context would have.
        FileDescriptor fdV2 = buildFileDescriptor("swap2.proto", "dev.example.SwapService", "MethodV2", "/v1/swap/v2");
        ManagedChannel channelV2 = startReflectionServer("dev.example.SwapService", fdV2);
        DynamicRouteLoader loaderV2 = buildLoader(props, registry, channelV2, Optional.empty(), noopMergeResolver());
        ServiceEndpointContext ctxV2 = loaderV2.buildContext(svcDef);

        // Act
        registry.replaceContext("swap-svc", ctxV2);

        // Assert: only the new route is routable; the old one is gone entirely (no merge).
        assertThat(registry.contextByServiceName("swap-svc").get().getRoutes()).hasSize(1);
        assertThat(registry.findByHttpKey("GET", "/v1/swap/v1")).isEmpty();
        assertThat(registry.findByHttpKey("GET", "/v1/swap/v2")).isPresent();
    }

    @Test
    @DisplayName("GIVEN a mix of DYNAMIC/PB_FILE and other-mode services WHEN load runs "
            + "SHOULD register a context only for the DYNAMIC and PB_FILE ones")
    void given_mixOfModes_when_load_should_registerOnlyDynamicAndPbFileContexts(@TempDir Path tempDir)
            throws Exception {
        // Arrange
        FileDescriptor dynFd = buildFileDescriptor("mix-dyn.proto", "dev.example.MixDynService", "Ping", null);
        ManagedChannel dynChannel = startReflectionServer("dev.example.MixDynService", dynFd);
        ServiceDefinition dynSvc = dynamicServiceDef("mix-dynamic");

        FileDescriptor pbFd = buildFileDescriptor("mix-pb.proto", "dev.example.MixPbService", "Ping", null);
        FileDescriptorSet fds =
                FileDescriptorSet.newBuilder().addFile(pbFd.toProto()).build();
        Path pbFile = tempDir.resolve("mix.pb");
        Files.write(pbFile, fds.toByteArray());
        ServiceDefinition pbSvc = pbFileServiceDef("mix-pbfile", pbFile.toString());

        ServiceDefinition staticSvc = new ServiceDefinition();
        staticSvc.setName("mix-static");
        staticSvc.setMode(GatewayMode.STATIC);

        GatewayProperties props = propsWith(dynSvc, pbSvc, staticSvc);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();

        // The loader is built once; its GrpcChannelFactory mock always returns dynChannel, which is
        // fine because PB_FILE never dials the channel for descriptor discovery.
        DynamicRouteLoader loader = buildLoader(props, registry, dynChannel, Optional.empty(), noopMergeResolver());

        // Act
        loader.load();

        // Assert
        assertThat(registry.contextByServiceName("mix-dynamic")).isPresent();
        assertThat(registry.contextByServiceName("mix-pbfile")).isPresent();
        assertThat(registry.contextByServiceName("mix-static")).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a DYNAMIC service with a backend that is unreachable (channel to nonexistent in-process server) "
                    + "WHEN buildContext is called SHOULD throw AND the channel SHOULD be shut down")
    void given_dynamicServiceWithUnreachableBackend_when_buildContext_should_throwAndShutdownChannel()
            throws Exception {
        // Arrange: Create a real channel to a nonexistent in-process server name. When buildContext
        // tries to use GrpcReflectionClient.listServices(), it will fail because nothing is listening.
        ManagedChannel unreachableChannel = InProcessChannelBuilder.forName("nonexistent-" + System.nanoTime())
                .directExecutor()
                .build();
        channels.add(unreachableChannel);

        ServiceDefinition svcDef = dynamicServiceDef("unreachable");
        GatewayProperties props = propsWith(svcDef);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, unreachableChannel, Optional.empty(), noopMergeResolver());

        // Act & Assert: buildContext must throw
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loader.buildContext(svcDef))
                .isInstanceOf(Exception.class);

        // Assert: the channel MUST be shut down (not left hanging), even though buildContext failed
        assertThat(unreachableChannel.isShutdown()).isTrue();
    }

    /**
     * Helper to build a descriptor with one service and three methods of different streaming
     * types: bidi, client-streaming, server-streaming. Each has an HTTP annotation.
     */
    private static FileDescriptor buildBidiTestFileDescriptor(String fileName, String serviceFqn) throws Exception {
        DescriptorProto empty = DescriptorProto.newBuilder()
                .setName("Empty")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        String emptyType = "." + qualifiedTypeName(serviceFqn, "Empty");

        // Build all three methods with their respective streaming modes and HTTP annotations
        MethodDescriptorProto bidiMethod = MethodDescriptorProto.newBuilder()
                .setName("Chat")
                .setInputType(emptyType)
                .setOutputType(emptyType)
                .setClientStreaming(true)
                .setServerStreaming(true)
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                AnnotationsProto.http,
                                HttpRule.newBuilder().setPost("/v1/chat").build())
                        .build())
                .build();

        MethodDescriptorProto clientStreamingMethod = MethodDescriptorProto.newBuilder()
                .setName("Upload")
                .setInputType(emptyType)
                .setOutputType(emptyType)
                .setClientStreaming(true)
                .setServerStreaming(false)
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                AnnotationsProto.http,
                                HttpRule.newBuilder().setPost("/v1/upload").build())
                        .build())
                .build();

        MethodDescriptorProto serverStreamingMethod = MethodDescriptorProto.newBuilder()
                .setName("Feed")
                .setInputType(emptyType)
                .setOutputType(emptyType)
                .setClientStreaming(false)
                .setServerStreaming(true)
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                AnnotationsProto.http,
                                HttpRule.newBuilder().setGet("/v1/feed").build())
                        .build())
                .build();

        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName(fileName)
                .setSyntax("proto3")
                .addMessageType(empty)
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(simpleName(serviceFqn))
                        .addMethod(bidiMethod)
                        .addMethod(clientStreamingMethod)
                        .addMethod(serverStreamingMethod)
                        .build());
        applyPackage(fileProto, serviceFqn);

        return FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[0]);
    }

    @Test
    @DisplayName(
            "GIVEN a bidirectional streaming method annotated with google.api.http POST rule "
                    + "WHEN the service is discovered via reflection SHOULD not register a route with REST binding for that method")
    void given_bidiMethodAnnotatedWithHttpRule_when_buildContext_should_notRegisterRestBinding() throws Exception {
        // Arrange
        FileDescriptor fd = buildBidiTestFileDescriptor("bidi.proto", "dev.example.BidiService");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.BidiService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("bidi-svc");
        GatewayProperties props = propsWith(svcDef);
        props.getGraphql().setEnabled(false); // Disable GraphQL for simplicity
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, inProcessChannel, Optional.empty(), noopMergeResolver());

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
    @DisplayName(
            "GIVEN a service with bidi, client-streaming, and server-streaming methods annotated with HTTP rules "
                    + "WHEN the service is discovered via reflection SHOULD still register REST routes for the non-bidi methods")
    void given_mixedStreamingMethodsAnnotatedWithHttp_when_buildContext_should_registerNonBidiRestRoutes()
            throws Exception {
        // Arrange
        FileDescriptor fd = buildBidiTestFileDescriptor("mixed-stream.proto", "dev.example.MixedStreamService");
        ManagedChannel inProcessChannel = startReflectionServer("dev.example.MixedStreamService", fd);

        ServiceDefinition svcDef = dynamicServiceDef("mixed-stream-svc");
        GatewayProperties props = propsWith(svcDef);
        props.getGraphql().setEnabled(false); // Disable GraphQL for simplicity
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        DynamicRouteLoader loader =
                buildLoader(props, registry, inProcessChannel, Optional.empty(), noopMergeResolver());

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

    /** Writes a descriptor set with one GET-bound method carrying {@code schema}; returns its path. */
    private static Path writeAnnotatedDescriptorSet(Path dir, String serviceFqn, MethodRule schema) throws Exception {
        String emptyType = "." + qualifiedTypeName(serviceFqn, "Empty");
        FileDescriptorProto.Builder fileProto = FileDescriptorProto.newBuilder()
                .setName("annotated.proto")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Empty")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(simpleName(serviceFqn))
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("GetDoc")
                                .setInputType(emptyType)
                                .setOutputType(emptyType)
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
        applyPackage(fileProto, serviceFqn);

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto.build(), new FileDescriptor[0]);
        Path pbFile = dir.resolve("annotated.pb");
        Files.write(
                pbFile,
                FileDescriptorSet.newBuilder().addFile(fd.toProto()).build().toByteArray());
        return pbFile;
    }

    @Test
    @DisplayName("GIVEN a method declaring auth_scopes and authz AND GraphQL disabled globally "
            + "WHEN buildContext SHOULD still put the scopes and authz rules on the REST route")
    void GIVEN_authScopesAndGraphQlDisabled_WHEN_buildContext_SHOULD_keepAccessRulesOnRestRoute(@TempDir Path tempDir)
            throws Exception {
        // Arrange
        MethodRule schema = MethodRule.newBuilder()
                .addAuthScopes("docs.read")
                .addAuthz(AuthzRule.newBuilder()
                        .setObjectType("document")
                        .setRelation("viewer")
                        .setIdField("id"))
                .build();
        Path pbFile = writeAnnotatedDescriptorSet(tempDir, "dev.example.DocService", schema);

        ServiceDefinition svcDef = pbFileServiceDef("docs", pbFile.toString());
        GatewayProperties props = propsWith(svcDef);
        props.getGraphql().setEnabled(false);
        DynamicRouteLoader loader = buildLoader(
                props, new GrpcRouteRegistry(), mock(ManagedChannel.class), Optional.empty(), noopMergeResolver());

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
    void GIVEN_authScopesWithoutFieldName_WHEN_buildContext_SHOULD_keepScopesOnRoute(@TempDir Path tempDir)
            throws Exception {
        // Arrange
        MethodRule schema = MethodRule.newBuilder().addAuthScopes("docs.read").build();
        Path pbFile = writeAnnotatedDescriptorSet(tempDir, "dev.example.DocService", schema);

        ServiceDefinition svcDef = pbFileServiceDef("docs", pbFile.toString());
        DynamicRouteLoader loader = buildLoader(
                propsWith(svcDef),
                new GrpcRouteRegistry(),
                mock(ManagedChannel.class),
                Optional.empty(),
                noopMergeResolver());

        // Act
        ServiceEndpointContext ctx = loader.buildContext(svcDef);

        // Assert
        assertThat(ctx.getRoutes()).hasSize(1);
        assertThat(ctx.getRoutes().get(0).getAuthScopes()).containsExactly("docs.read");
    }
}
