package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

/**
 * BDD-style integration tests for GrpcDescriptorCache.
 *
 * Tests cover caching behavior of gRPC MethodDescriptor (keyed by ServiceEndpointContext + method name)
 * and DynamicMessageMarshaller (keyed by proto Descriptor identity).
 *
 * Uses real proto Descriptors built via FileDescriptor.buildFrom() for integration scenarios,
 * and mocks for isolated unit tests of cache behavior.
 */
class GrpcDescriptorCacheTest {

    private GrpcDescriptorCache cache;

    @BeforeEach
    void setUp() {
        cache = new GrpcDescriptorCache();
        MockitoAnnotations.openMocks(this);
    }

    // ── MethodDescriptor caching tests ──

    @Test
    @DisplayName(
            "Given resolve called for a method, when called again with same context and method, should return identical cached instance")
    void given_resolveCalledForMethod_when_calledAgainWithSameContextAndMethod_should_returnIdenticalCachedInstance()
            throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.TestService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> first = cache.resolve(ctx, sd, md, MethodType.UNARY);
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> second = cache.resolve(ctx, sd, md, MethodType.UNARY);

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName(
            "Given resolve called, when called with different context for same service and method, should return different instances")
    void given_resolveCalled_when_calledWithDifferentContextForSameMethod_should_returnDifferentInstances()
            throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.TestService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx1 = new ServiceEndpointContext(new ServiceDefinition(), null);
        ServiceEndpointContext ctx2 = new ServiceEndpointContext(new ServiceDefinition(), null);

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> md1 = cache.resolve(ctx1, sd, md, MethodType.UNARY);
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> md2 = cache.resolve(ctx2, sd, md, MethodType.UNARY);

        assertThat(md1).isNotSameAs(md2);
    }

    @Test
    @DisplayName(
            "Given resolve called, should return MethodDescriptor with correct fullMethodName in format 'fqn/MethodName'")
    void given_serviceAndMethod_when_resolveCalled_should_returnMethodDescriptorWithCorrectFullMethodName()
            throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.MyService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> result = cache.resolve(ctx, sd, md, MethodType.UNARY);

        assertThat(result.getFullMethodName()).isEqualTo("com.example.MyService/GetItem");
    }

    @Test
    @DisplayName("Given resolve called for UNARY method, should return MethodDescriptor with type UNARY")
    void given_unaryMethod_when_resolveCalled_should_returnMethodDescriptorWithTypeUnary() throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.TestService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> result = cache.resolve(ctx, sd, md, MethodType.UNARY);

        assertThat(result.getType()).isEqualTo(MethodType.UNARY);
    }

    @Test
    @DisplayName(
            "Given resolve called for SERVER_STREAMING method, should return MethodDescriptor with type SERVER_STREAMING")
    void given_serverStreamingMethod_when_resolveCalled_should_returnMethodDescriptorWithTypeServerStreaming()
            throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.TestService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> result =
                cache.resolve(ctx, sd, md, MethodType.SERVER_STREAMING);

        assertThat(result.getType()).isEqualTo(MethodType.SERVER_STREAMING);
    }

    @Test
    @DisplayName("Given resolve called, should return MethodDescriptor with request and response marshallers")
    void given_serviceAndMethod_when_resolveCalled_should_returnMethodDescriptorWithRequestAndResponseMarshallers()
            throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.TestService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> result = cache.resolve(ctx, sd, md, MethodType.UNARY);

        assertThat(result.getRequestMarshaller()).isNotNull().isInstanceOf(DynamicMessageMarshaller.class);
        assertThat(result.getResponseMarshaller()).isNotNull().isInstanceOf(DynamicMessageMarshaller.class);
    }

    @Test
    @DisplayName(
            "Given multiple different methods in service, when resolve called for each, should cache each independently")
    void given_multipleDifferentMethods_when_resolveCalledForEach_should_cacheIndependently() throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd =
                createMockServiceDescriptorWithTwoMethods("com.example.MultiService", reqDescriptor, respDescriptor);
        MethodDescriptor md1 = sd.getMethods().get(0);
        MethodDescriptor md2 = sd.getMethods().get(1);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> result1 =
                cache.resolve(ctx, sd, md1, MethodType.UNARY);
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> result2 =
                cache.resolve(ctx, sd, md2, MethodType.UNARY);

        // Different methods should produce different descriptors
        assertThat(result1).isNotSameAs(result2);
        assertThat(result1.getFullMethodName()).isNotEqualTo(result2.getFullMethodName());
    }

    // ── Marshaller caching tests ──

    @Test
    @DisplayName(
            "Given marshallerFor called for a Descriptor, when called again with same Descriptor, should return identical cached instance")
    void
            given_marshallerForCalledForDescriptor_when_calledAgainWithSameDescriptor_should_returnIdenticalCachedInstance()
                    throws Exception {
        Descriptor descriptor = buildMessageDescriptor("Message");

        DynamicMessageMarshaller first = cache.marshallerFor(descriptor);
        DynamicMessageMarshaller second = cache.marshallerFor(descriptor);

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("Given marshallerFor called with different Descriptors, should return different instances")
    void given_differentDescriptors_when_marshallerForCalled_should_returnDifferentInstances() throws Exception {
        Descriptor descriptor1 = buildMessageDescriptor("Message1");
        Descriptor descriptor2 = buildMessageDescriptor("Message2");

        DynamicMessageMarshaller marshaller1 = cache.marshallerFor(descriptor1);
        DynamicMessageMarshaller marshaller2 = cache.marshallerFor(descriptor2);

        assertThat(marshaller1).isNotSameAs(marshaller2);
    }

    // ── Cache size tests ──

    @Test
    @DisplayName("Given contextCacheSize called after resolve, should return 1 when one context was used")
    void given_oneContextResolved_when_contextCacheSizeCalled_should_returnOne() throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.TestService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        cache.resolve(ctx, sd, md, MethodType.UNARY);

        assertThat(cache.contextCacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given marshallerCacheSize called after resolve, should return 2 (request and response marshallers)")
    void given_oneMethodResolved_when_marshallerCacheSizeCalled_should_returnTwoForRequestAndResponseMarshallers()
            throws Exception {
        Descriptor reqDescriptor = buildMessageDescriptor("Request");
        Descriptor respDescriptor = buildMessageDescriptor("Response");
        ServiceDescriptor sd = createMockServiceDescriptor("com.example.TestService", reqDescriptor, respDescriptor);
        MethodDescriptor md = sd.getMethods().get(0);
        ServiceEndpointContext ctx = new ServiceEndpointContext(new ServiceDefinition(), null);

        cache.resolve(ctx, sd, md, MethodType.UNARY);

        // One marshaller for request type, one for response type
        assertThat(cache.marshallerCacheSize()).isEqualTo(2);
    }

    // ── Helper methods to build real proto descriptors ──

    private static Descriptor buildMessageDescriptor(String messageName) throws Exception {
        DescriptorProto messageProto = DescriptorProto.newBuilder()
                .setName(messageName)
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT64)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("test_" + messageName.toLowerCase() + ".proto")
                .setSyntax("proto3")
                .addMessageType(messageProto)
                .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
        return fd.findMessageTypeByName(messageName);
    }

    /**
     * Creates a mock ServiceDescriptor with one method (GetItem) that has the given
     * request and response types. The service FQN is taken from the fqn parameter.
     */
    private static ServiceDescriptor createMockServiceDescriptor(
            String serviceFqn, Descriptor requestType, Descriptor responseType) throws Exception {

        // Build a real service via FileDescriptor to avoid descriptor validation errors
        int lastDot = serviceFqn.lastIndexOf('.');
        String pkg = lastDot >= 0 ? serviceFqn.substring(0, lastDot) : "";
        String serviceName = lastDot >= 0 ? serviceFqn.substring(lastDot + 1) : serviceFqn;

        // Build method proto
        MethodDescriptorProto methodProto = MethodDescriptorProto.newBuilder()
                .setName("GetItem")
                .setInputType(requestType.getFullName())
                .setOutputType(responseType.getFullName())
                .build();

        // Build service proto
        ServiceDescriptorProto serviceProto = ServiceDescriptorProto.newBuilder()
                .setName(serviceName)
                .addMethod(methodProto)
                .build();

        // Build file proto combining request, response, and service
        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("service.proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Request")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Response")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("result")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addService(serviceProto)
                .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
        return fd.findServiceByName(serviceName);
    }

    /**
     * Creates a mock ServiceDescriptor with two methods (GetItem and ListItems).
     */
    private static ServiceDescriptor createMockServiceDescriptorWithTwoMethods(
            String serviceFqn, Descriptor requestType, Descriptor responseType) throws Exception {

        int lastDot = serviceFqn.lastIndexOf('.');
        String pkg = lastDot >= 0 ? serviceFqn.substring(0, lastDot) : "";
        String serviceName = lastDot >= 0 ? serviceFqn.substring(lastDot + 1) : serviceFqn;

        // Build two methods
        MethodDescriptorProto method1 = MethodDescriptorProto.newBuilder()
                .setName("GetItem")
                .setInputType("Request")
                .setOutputType("Response")
                .build();

        MethodDescriptorProto method2 = MethodDescriptorProto.newBuilder()
                .setName("ListItems")
                .setInputType("Request")
                .setOutputType("Response")
                .build();

        // Build service with two methods
        ServiceDescriptorProto serviceProto = ServiceDescriptorProto.newBuilder()
                .setName(serviceName)
                .addMethod(method1)
                .addMethod(method2)
                .build();

        // Build file
        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("multi_service.proto")
                .setPackage(pkg)
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Request")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_INT64)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Response")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("result")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .build())
                        .build())
                .addService(serviceProto)
                .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
        return fd.findServiceByName(serviceName);
    }
}
