package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * BDD-style unit tests for GrpcProxyHandlerRegistry.
 *
 * Strategy:
 * - GrpcRouteRegistry is mocked with Mockito: it is the routing lookup boundary.
 * - GrpcDescriptorCache is used as a real instance: it has no external dependencies and
 *   its caching behavior is part of what we want to observe.
 * - ServiceEndpointContext is built as a real instance populated with inline proto
 *   descriptors (same technique as DynamicSerializerFactoryTest), so we never mock
 *   proto Descriptor objects.
 * - ManagedChannel on the context is mocked because lookupMethod() itself does not
 *   invoke the channel; only the returned handler's closure would, and we do not
 *   exercise the bridge logic here.
 */
@ExtendWith(MockitoExtension.class)
class GrpcProxyHandlerRegistryTest {

    private static final String SERVICE_FQN = "dev.example.GreetingService";
    private static final String METHOD_NAME = "SayHello";
    private static final String FULL_METHOD = SERVICE_FQN + "/" + METHOD_NAME;

    @Mock
    private GrpcRouteRegistry routeRegistry;

    private GrpcDescriptorCache descriptorCache;
    private GrpcProxyHandlerRegistry registry;

    // Shared proto descriptors built once per test class load
    private static FileDescriptor testFileDescriptor;
    private static ServiceDescriptor testServiceDescriptor;
    private static MethodDescriptor testMethodDescriptor;

    static {
        try {
            testFileDescriptor = buildTestFileDescriptor();
            testServiceDescriptor = testFileDescriptor.findServiceByName("GreetingService");
            testMethodDescriptor = testServiceDescriptor.findMethodByName(METHOD_NAME);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeEach
    void setUp() {
        descriptorCache = new GrpcDescriptorCache();
        registry = new GrpcProxyHandlerRegistry(routeRegistry, descriptorCache);
    }

    // -------------------------------------------------------------------------
    // 1. Malformed full method name — no slash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given fullMethodName with no slash, when lookupMethod, should return null")
    void GIVEN_fullMethodNameWithNoSlash_WHEN_lookupMethod_SHOULD_returnNull() {
        ServerMethodDefinition<?, ?> result = registry.lookupMethod("NoSlashAtAll", "authority");

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // 2. No ServiceEndpointContext registered for the serviceFqn
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given no context registered for serviceFqn, when lookupMethod, should return null")
    void GIVEN_noContextForServiceFqn_WHEN_lookupMethod_SHOULD_returnNull() {
        when(routeRegistry.contextByServiceFqn(SERVICE_FQN)).thenReturn(Optional.empty());

        ServerMethodDefinition<?, ?> result = registry.lookupMethod(FULL_METHOD, "authority");

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // 3. Context registered but method not present in that context
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given context registered but method absent, when lookupMethod, should return null")
    void GIVEN_contextRegisteredButMethodAbsent_WHEN_lookupMethod_SHOULD_returnNull() {
        ServiceEndpointContext ctx = buildContext(SERVICE_FQN, testServiceDescriptor);
        // Context has the service but NOT the method in its methodByKey index
        when(routeRegistry.contextByServiceFqn(SERVICE_FQN)).thenReturn(Optional.of(ctx));

        ServerMethodDefinition<?, ?> result = registry.lookupMethod(SERVICE_FQN + "/NonExistentMethod", "authority");

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // 4. Happy path — valid serviceFqn and methodName
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given valid serviceFqn and methodName, when lookupMethod, should return non-null ServerMethodDefinition")
    void GIVEN_validServiceFqnAndMethodName_WHEN_lookupMethod_SHOULD_returnServerMethodDefinition() {
        ServiceEndpointContext ctx = buildContextWithMethod(SERVICE_FQN, testServiceDescriptor, testMethodDescriptor);
        when(routeRegistry.contextByServiceFqn(SERVICE_FQN)).thenReturn(Optional.of(ctx));

        ServerMethodDefinition<?, ?> result = registry.lookupMethod(FULL_METHOD, "authority");

        assertThat(result).isNotNull();
        assertThat(result.getMethodDescriptor().getFullMethodName()).isEqualTo(FULL_METHOD);
    }

    // -------------------------------------------------------------------------
    // 5. Same call twice — second call hits the definition cache
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given same fullMethodName called twice with same context, when lookupMethod both times, should return same cached instance")
    void GIVEN_sameFullMethodNameCalledTwice_WHEN_lookupMethodBothTimes_SHOULD_returnSameCachedInstance() {
        ServiceEndpointContext ctx = buildContextWithMethod(SERVICE_FQN, testServiceDescriptor, testMethodDescriptor);
        when(routeRegistry.contextByServiceFqn(SERVICE_FQN)).thenReturn(Optional.of(ctx));

        ServerMethodDefinition<?, ?> first = registry.lookupMethod(FULL_METHOD, "authority");
        ServerMethodDefinition<?, ?> second = registry.lookupMethod(FULL_METHOD, "authority");

        assertThat(first).isSameAs(second);
    }

    // -------------------------------------------------------------------------
    // 6. Invalidate evicts the cached entry — subsequent lookup rebuilds it
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given invalidate called with oldCtx, when lookupMethod called after, should return new instance")
    void GIVEN_invalidateCalled_WHEN_lookupMethodAfter_SHOULD_returnNewInstance() {
        ServiceEndpointContext ctx = buildContextWithMethod(SERVICE_FQN, testServiceDescriptor, testMethodDescriptor);
        when(routeRegistry.contextByServiceFqn(SERVICE_FQN)).thenReturn(Optional.of(ctx));

        // Prime the cache
        ServerMethodDefinition<?, ?> before = registry.lookupMethod(FULL_METHOD, "authority");

        // Evict
        registry.invalidate(ctx);

        // Lookup again — must rebuild (new instance, not same reference)
        ServerMethodDefinition<?, ?> after = registry.lookupMethod(FULL_METHOD, "authority");

        assertThat(after).isNotNull();
        assertThat(after).isNotSameAs(before);
    }

    // -------------------------------------------------------------------------
    // 7. Invalidate one context does not evict entries for a different context
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given two contexts for different services, when invalidate called with ctx1, should not evict entries for ctx2")
    void GIVEN_twoContexts_WHEN_invalidateCtx1_SHOULD_notEvictCtx2Entries() throws Exception {
        // Build a second service with its own proto descriptors
        String svc2Fqn = "dev.example.FarewellService";
        FileDescriptor fd2 = buildSecondFileDescriptor();
        ServiceDescriptor sd2 = fd2.findServiceByName("FarewellService");
        MethodDescriptor md2 = sd2.findMethodByName("SayGoodbye");

        ServiceEndpointContext ctx1 = buildContextWithMethod(SERVICE_FQN, testServiceDescriptor, testMethodDescriptor);
        ServiceEndpointContext ctx2 = buildContextWithMethod(svc2Fqn, sd2, md2);

        String fullMethod2 = svc2Fqn + "/SayGoodbye";

        when(routeRegistry.contextByServiceFqn(SERVICE_FQN)).thenReturn(Optional.of(ctx1));
        when(routeRegistry.contextByServiceFqn(svc2Fqn)).thenReturn(Optional.of(ctx2));

        // Prime both caches
        registry.lookupMethod(FULL_METHOD, "authority");
        ServerMethodDefinition<?, ?> def2Before = registry.lookupMethod(fullMethod2, "authority");

        // Evict only ctx1
        registry.invalidate(ctx1);

        // ctx2's cached definition must still be the exact same reference
        ServerMethodDefinition<?, ?> def2After = registry.lookupMethod(fullMethod2, "authority");
        assertThat(def2After).isSameAs(def2Before);
    }

    // -------------------------------------------------------------------------
    // 8. Invalidate with null argument — must not throw
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given invalidate called with null, should not throw")
    void GIVEN_invalidateCalledWithNull_SHOULD_notThrow() {
        assertThatCode(() -> registry.invalidate(null)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // 9. Invalidate context that has no cached entries — must not throw
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given invalidate called with context that has no cached entries, should not throw")
    void GIVEN_invalidateCalledWithContextWithNoCachedEntries_SHOULD_notThrow() {
        ServiceEndpointContext ctx = buildContext(SERVICE_FQN, testServiceDescriptor);

        assertThatCode(() -> registry.invalidate(ctx)).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // 10. getServices() always returns an empty list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given getServices called, should return empty list")
    void GIVEN_getServicesCalled_SHOULD_returnEmptyList() {
        assertThat(registry.getServices()).isEmpty();
    }

    // =========================================================================
    // Test helpers
    @Test
    @DisplayName(
            "Given a caller that sends its own x-principal, when the call is proxied, should send only 'anonymous' downstream")
    @SuppressWarnings("unchecked")
    void GIVEN_callerSendsPrincipal_WHEN_callProxied_SHOULD_sendOnlyAnonymousDownstream() {
        ServiceEndpointContext ctx = buildContextWithMethod(SERVICE_FQN, testServiceDescriptor, testMethodDescriptor);
        when(routeRegistry.contextByServiceFqn(SERVICE_FQN)).thenReturn(Optional.of(ctx));
        ClientCall<DynamicMessage, DynamicMessage> clientCall = mock(ClientCall.class);
        when(ctx.getChannel().newCall(any(io.grpc.MethodDescriptor.class), any(CallOptions.class)))
                .thenReturn((ClientCall) clientCall);
        ServerMethodDefinition<DynamicMessage, DynamicMessage> definition =
                (ServerMethodDefinition<DynamicMessage, DynamicMessage>)
                        registry.lookupMethod(FULL_METHOD, "authority");

        Metadata inbound = new Metadata();
        inbound.put(PRINCIPAL_KEY, "admin");
        inbound.put(PRINCIPAL_KEY, "root");
        inbound.put(AUTHORIZATION_KEY, "Bearer token-abc123");
        definition.getServerCallHandler().startCall(mock(ServerCall.class), inbound);

        ArgumentCaptor<Metadata> outbound = ArgumentCaptor.forClass(Metadata.class);
        verify(clientCall).start(any(), outbound.capture());
        assertThat(outbound.getValue().getAll(PRINCIPAL_KEY)).containsExactly("anonymous");
        assertThat(outbound.getValue().getAll(AUTHORIZATION_KEY)).containsExactly("Bearer token-abc123");
    }

    // =========================================================================

    private static final Metadata.Key<String> PRINCIPAL_KEY =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Builds a context that has the service in its serviceByFqn index but has
     * NO methods in its methodByKey index (simulates a context without the requested method).
     */
    private static ServiceEndpointContext buildContext(String serviceFqn, ServiceDescriptor sd) {
        ServiceDefinition config = new ServiceDefinition();
        config.setName("test-svc");
        config.setHost("localhost");
        config.setPort(9090);

        ManagedChannel channel = mock(ManagedChannel.class);
        ServiceEndpointContext ctx = new ServiceEndpointContext(config, channel);
        ctx.setServiceByFqn(Map.of(serviceFqn, sd));
        ctx.setMethodByKey(Map.of()); // intentionally empty
        return ctx;
    }

    /**
     * Builds a fully-populated context: service in serviceByFqn, method in methodByKey.
     */
    private static ServiceEndpointContext buildContextWithMethod(
            String serviceFqn, ServiceDescriptor sd, MethodDescriptor md) {
        ServiceDefinition config = new ServiceDefinition();
        config.setName("test-svc");
        config.setHost("localhost");
        config.setPort(9090);

        ManagedChannel channel = mock(ManagedChannel.class);
        ServiceEndpointContext ctx = new ServiceEndpointContext(config, channel);
        ctx.setServiceByFqn(Map.of(serviceFqn, sd));
        ctx.setMethodByKey(Map.of(serviceFqn + "/" + md.getName(), md));
        return ctx;
    }

    /**
     * Builds a minimal proto FileDescriptor containing:
     *   package dev.example;
     *   message HelloRequest  { string name = 1; }
     *   message HelloResponse { string greeting = 1; }
     *   service GreetingService { rpc SayHello(HelloRequest) returns (HelloResponse); }
     */
    private static FileDescriptor buildTestFileDescriptor() throws Exception {
        DescriptorProto helloRequest = DescriptorProto.newBuilder()
                .setName("HelloRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto helloResponse = DescriptorProto.newBuilder()
                .setName("HelloResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("greeting")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        ServiceDescriptorProto greetingService = ServiceDescriptorProto.newBuilder()
                .setName("GreetingService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("SayHello")
                        .setInputType(".dev.example.HelloRequest")
                        .setOutputType(".dev.example.HelloResponse")
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("greeting.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addMessageType(helloRequest)
                .addMessageType(helloResponse)
                .addService(greetingService)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }

    /**
     * Builds a second proto FileDescriptor for a different service:
     *   package dev.example;
     *   message GoodbyeRequest  { string name = 1; }
     *   message GoodbyeResponse { string farewell = 1; }
     *   service FarewellService { rpc SayGoodbye(GoodbyeRequest) returns (GoodbyeResponse); }
     */
    private static FileDescriptor buildSecondFileDescriptor() throws Exception {
        DescriptorProto goodbyeRequest = DescriptorProto.newBuilder()
                .setName("GoodbyeRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto goodbyeResponse = DescriptorProto.newBuilder()
                .setName("GoodbyeResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("farewell")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        ServiceDescriptorProto farewellService = ServiceDescriptorProto.newBuilder()
                .setName("FarewellService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("SayGoodbye")
                        .setInputType(".dev.example.GoodbyeRequest")
                        .setOutputType(".dev.example.GoodbyeResponse")
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("farewell.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addMessageType(goodbyeRequest)
                .addMessageType(goodbyeResponse)
                .addService(farewellService)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }
}
