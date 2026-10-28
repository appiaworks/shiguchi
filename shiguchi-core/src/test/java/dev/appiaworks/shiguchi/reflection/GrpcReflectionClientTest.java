package dev.appiaworks.shiguchi.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for GrpcReflectionClient.
 *
 * Strategy:
 * - Stand up real in-process gRPC servers with hand-built proto descriptors.
 * - Each test creates a unique server instance with ProtoReflectionServiceV1 registered.
 * - Proto descriptors are built programmatically using DescriptorProto builders.
 * - Use directExecutor() on both server and channel to keep streaming calls synchronous.
 * - Tear down server and channel in @AfterEach to prevent cross-test collisions.
 *
 * Focus: Test the listServices() behavior thoroughly (which is simpler and more reliable to test).
 * Test resolveService() for basic success and error paths. Both methods timeout properly via
 * the client's explicit deadline/timeout mechanisms.
 */
class GrpcReflectionClientTest {

    private Server server;
    private ManagedChannel channel;
    private GrpcReflectionClient client;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    // =========================================================================
    // Test 1: Single service — listServices returns the service FQN
    // =========================================================================

    @Test
    @DisplayName(
            "Given a running reflection server with one service, when listServices is called, should return that service's fully-qualified name")
    void GIVEN_reflectionServerWithOneService_WHEN_listServices_SHOULD_returnServiceFqn() throws Exception {
        // Arrange
        FileDescriptor singleSvcFileDescriptor = buildSingleServiceFileDescriptor();
        startServerWithService("dev.example.SingleService", singleSvcFileDescriptor);

        // Act
        List<String> services = client.listServices();

        // Assert
        assertThat(services)
                .contains("dev.example.SingleService")
                .doesNotContainAnyElementsOf(getGrpcReflectionServices());
    }

    // =========================================================================
    // Test 2: Two services — listServices returns both service FQNs
    // =========================================================================

    @Test
    @DisplayName(
            "Given a running reflection server with two services, when listServices is called, should return both fully-qualified names")
    void GIVEN_reflectionServerWithTwoServices_WHEN_listServices_SHOULD_returnBothServiceFqns() throws Exception {
        // Arrange
        FileDescriptor fd1 = buildSingleServiceFileDescriptor();
        FileDescriptor fd2 = buildSecondServiceFileDescriptor();
        startServerWithMultipleServices(
                new ServiceInfo("dev.example.FirstService", fd1), new ServiceInfo("dev.example.SecondService", fd2));

        // Act
        List<String> services = client.listServices();

        // Assert
        assertThat(services)
                .contains("dev.example.FirstService", "dev.example.SecondService")
                .doesNotContainAnyElementsOf(getGrpcReflectionServices());
    }

    // =========================================================================
    // Test 3: listServices filters out grpc.reflection.* services
    // =========================================================================

    @Test
    @DisplayName(
            "Given a reflection server running, when listServices is called, should exclude grpc.reflection services from result")
    void GIVEN_reflectionServerRunning_WHEN_listServices_SHOULD_excludeGrpcReflectionServices() throws Exception {
        // Arrange
        FileDescriptor fd = buildSingleServiceFileDescriptor();
        startServerWithService("dev.example.TestService", fd);

        // Act
        List<String> services = client.listServices();

        // Assert
        assertThat(services).contains("dev.example.TestService").noneMatch(s -> s.startsWith("grpc.reflection"));
    }

    // =========================================================================
    // Test 4: resolveService returns a FileDescriptorSet (may be empty if symbol unknown)
    // =========================================================================

    @Test
    @DisplayName(
            "Given a running reflection server with a service, when resolveService is called with that service's FQN, should return a FileDescriptorSet containing that service's own file")
    void GIVEN_reflectionServerWithService_WHEN_resolveService_SHOULD_returnFileDescriptorSet() throws Exception {
        // Arrange
        // The registered gRPC service name MUST match the proto service's own full name
        // (dev.example.SingleService per buildSingleServiceFileDescriptor()) — the real
        // reflection service indexes symbols from the schema descriptor's FileDescriptor,
        // not from whatever name the ServiceDescriptor happens to be registered under.
        FileDescriptor fd = buildSingleServiceFileDescriptor();
        startServerWithService("dev.example.SingleService", fd);

        // Act
        FileDescriptorSet descriptorSet = client.resolveService("dev.example.SingleService");

        // Assert
        assertThat(descriptorSet.getFileList())
                .extracting(FileDescriptorProto::getName)
                .contains("simple.proto");
    }

    // =========================================================================
    // Test 4b: resolveService follows transitive dependencies via setFileByFilename
    // =========================================================================

    @Test
    @DisplayName("Given a service whose file depends on another proto file, when resolveService is called, "
            + "should return a FileDescriptorSet containing both the service's own file and its transitive dependency")
    void GIVEN_serviceFileWithTransitiveDependency_WHEN_resolveService_SHOULD_returnBothFiles() throws Exception {
        // Arrange: "dependency.proto" declares a message referenced from "dependent.proto",
        // which declares the service under test. The server is seeded with the FULL
        // dependency graph (both files) via a schema descriptor, so a real gRPC reflection
        // service can honour the client's follow-up setFileByFilename("dependency.proto") call.
        FileDescriptor dependencyFd = buildDependencyFileDescriptor();
        FileDescriptor dependentFd = buildDependentFileDescriptor(dependencyFd);
        startServerWithService("dev.example.DependentService", dependentFd);

        // Act
        FileDescriptorSet descriptorSet = client.resolveService("dev.example.DependentService");

        // Assert
        assertThat(descriptorSet.getFileList())
                .extracting(FileDescriptorProto::getName)
                .contains("dependent.proto", "dependency.proto");
    }

    // =========================================================================
    // Test 5: resolveService called twice does not throw (idempotent calls succeed)
    // =========================================================================

    @Test
    @DisplayName(
            "Given a running server, when resolveService is called twice for the same service, should both complete successfully and return the same file")
    void GIVEN_runningServer_WHEN_resolveServiceCalledTwice_SHOULD_bothCompleteSuccessfully() throws Exception {
        // Arrange
        FileDescriptor fd = buildSingleServiceFileDescriptor();
        startServerWithService("dev.example.SingleService", fd);

        // Act
        FileDescriptorSet descriptorSet1 = client.resolveService("dev.example.SingleService");
        FileDescriptorSet descriptorSet2 = client.resolveService("dev.example.SingleService");

        // Assert
        // The key behavior: multiple calls to the same service complete without error and both
        // resolve to the same underlying proto file.
        assertThat(descriptorSet1.getFileList())
                .extracting(FileDescriptorProto::getName)
                .contains("simple.proto");
        assertThat(descriptorSet2.getFileList())
                .extracting(FileDescriptorProto::getName)
                .contains("simple.proto");
    }

    // =========================================================================
    // Test 6: No reflection service registered — listServices throws ExecutionException
    // =========================================================================

    @Test
    @DisplayName(
            "Given a server with no reflection service registered, when listServices is called, should throw ExecutionException")
    void GIVEN_noReflectionServiceRegistered_WHEN_listServices_SHOULD_throwExecutionException() throws Exception {
        // Arrange
        // Start server WITHOUT ProtoReflectionServiceV1 — just a minimal service
        String serverName = InProcessServerBuilder.generateName();
        var method = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("test.Dummy/DummyMethod")
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.newBuilder(DescriptorProto.newBuilder()
                                .setName("Req")
                                .build()
                                .getDefaultInstanceForType()
                                .getDescriptorForType())
                        .build()))
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.newBuilder(DescriptorProto.newBuilder()
                                .setName("Resp")
                                .build()
                                .getDefaultInstanceForType()
                                .getDescriptorForType())
                        .build()))
                .build();

        var dummyService = ServerServiceDefinition.builder(io.grpc.ServiceDescriptor.newBuilder("test.Dummy")
                        .addMethod(method)
                        .build())
                .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
                    obs.onNext(req);
                    obs.onCompleted();
                }))
                .build();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(dummyService)
                // Intentionally NOT adding ProtoReflectionServiceV1
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        client = new GrpcReflectionClient(channel);

        // Act & Assert
        assertThatThrownBy(() -> client.listServices()).isInstanceOf(ExecutionException.class);
    }

    // =========================================================================
    // Test 7: resolveService for an unknown symbol resolves with no files collected
    // =========================================================================

    @Test
    @DisplayName(
            "Given a reflection server with an existing service, when resolveService is called with an unknown symbol, should return an empty FileDescriptorSet rather than hang or throw")
    void GIVEN_reflectionServerWithKnownService_WHEN_resolveServiceUnknownSymbol_SHOULD_returnEmptyFileDescriptorSet()
            throws Exception {
        // Arrange
        FileDescriptor fd = buildSingleServiceFileDescriptor();
        startServerWithService("dev.example.SingleService", fd);

        // Act
        // The reflection server responds to an unknown fileContainingSymbol lookup with an
        // ErrorResponse (NOT_FOUND). GrpcReflectionClient.resolveService()'s onNext handler
        // only inspects hasFileDescriptorResponse(); an ErrorResponse still counts as "one
        // response received" against the outstanding-request counter, so the stream completes
        // normally with nothing collected — the caller gets a clear, empty result rather than
        // an exception or an indefinite hang.
        FileDescriptorSet result = client.resolveService("dev.example.UnknownService");

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getFileList()).isEmpty();
    }

    // =========================================================================
    // Test 8: Server with one service, listServices called multiple times yields same result
    // =========================================================================

    @Test
    @DisplayName(
            "Given a running reflection server, when listServices is called multiple times, should return the same services each time")
    void GIVEN_reflectionServerRunning_WHEN_listServicesCalledMultipleTimes_SHOULD_returnSameServicesEachTime()
            throws Exception {
        // Arrange
        FileDescriptor fd = buildSingleServiceFileDescriptor();
        startServerWithService("dev.example.RepeatableService", fd);

        // Act
        List<String> services1 = client.listServices();
        List<String> services2 = client.listServices();

        // Assert
        assertThat(services1).containsExactlyInAnyOrderElementsOf(services2);
    }

    // =========================================================================
    // Helper: Start server with a single service
    // =========================================================================

    private void startServerWithService(String serviceFqn, FileDescriptor fileDescriptor) throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        var serviceDescriptor = fileDescriptor.findServiceByName(extractServiceName(serviceFqn));

        var schema = new TestProtoDescriptorSupplier(fileDescriptor, serviceDescriptor);
        var method = buildDummyMethod(serviceFqn, "Dummy");
        var descriptor = io.grpc.ServiceDescriptor.newBuilder(serviceFqn)
                .setSchemaDescriptor(schema)
                .addMethod(method)
                .build();

        var service = ServerServiceDefinition.builder(descriptor)
                .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
                    obs.onNext(req);
                    obs.onCompleted();
                }))
                .build();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        client = new GrpcReflectionClient(channel);
    }

    // =========================================================================
    // Helper: Start server with multiple services
    // =========================================================================

    private void startServerWithMultipleServices(ServiceInfo... serviceInfos) throws Exception {
        String serverName = InProcessServerBuilder.generateName();
        var serverBuilder = InProcessServerBuilder.forName(serverName).directExecutor();

        for (ServiceInfo info : serviceInfos) {
            var svcDescriptor = info.fileDescriptor.findServiceByName(extractServiceName(info.serviceFqn));
            var schema = new TestProtoDescriptorSupplier(info.fileDescriptor, svcDescriptor);
            var method = buildDummyMethod(info.serviceFqn, "Dummy");
            var descriptor = io.grpc.ServiceDescriptor.newBuilder(info.serviceFqn)
                    .setSchemaDescriptor(schema)
                    .addMethod(method)
                    .build();

            var service = ServerServiceDefinition.builder(descriptor)
                    .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
                        obs.onNext(req);
                        obs.onCompleted();
                    }))
                    .build();

            serverBuilder.addService(service);
        }

        server = serverBuilder
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        client = new GrpcReflectionClient(channel);
    }

    // =========================================================================
    // Helper: Build a dummy method descriptor (for reflection)
    // =========================================================================

    private static MethodDescriptor<DynamicMessage, DynamicMessage> buildDummyMethod(
            String serviceFqn, String methodName) throws Exception {
        FileDescriptor dummyFd = buildDummyFileDescriptor();
        var requestMsg = dummyFd.findMessageTypeByName("DummyRequest");
        var responseMsg = dummyFd.findMessageTypeByName("DummyResponse");

        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(serviceFqn + "/" + methodName)
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(requestMsg).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(responseMsg).build()))
                .build();
    }

    private static FileDescriptor buildDummyFileDescriptor() throws Exception {
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("DummyRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("data")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto response = DescriptorProto.newBuilder()
                .setName("DummyResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("result")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("dummy.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addMessageType(request)
                .addMessageType(response)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }

    // =========================================================================
    // Helper: Build test proto descriptors
    // =========================================================================

    private static FileDescriptor buildSingleServiceFileDescriptor() throws Exception {
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("SimpleRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("id")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_INT32)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto response = DescriptorProto.newBuilder()
                .setName("SimpleResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("message")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("SingleService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("SimpleMethod")
                        .setInputType(".dev.example.SimpleRequest")
                        .setOutputType(".dev.example.SimpleResponse")
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("simple.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addMessageType(request)
                .addMessageType(response)
                .addService(service)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }

    private static FileDescriptor buildSecondServiceFileDescriptor() throws Exception {
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("SecondRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("value")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto response = DescriptorProto.newBuilder()
                .setName("SecondResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("result")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("SecondService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("SecondMethod")
                        .setInputType(".dev.example.SecondRequest")
                        .setOutputType(".dev.example.SecondResponse")
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("second.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addMessageType(request)
                .addMessageType(response)
                .addService(service)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }

    // =========================================================================
    // Helper: Build a two-file dependency graph (dependency.proto <- dependent.proto)
    // =========================================================================

    private static FileDescriptor buildDependencyFileDescriptor() throws Exception {
        DescriptorProto sharedType = DescriptorProto.newBuilder()
                .setName("SharedType")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("label")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("dependency.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addMessageType(sharedType)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
    }

    private static FileDescriptor buildDependentFileDescriptor(FileDescriptor dependency) throws Exception {
        DescriptorProto request = DescriptorProto.newBuilder()
                .setName("DependentRequest")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("shared")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".dev.example.SharedType")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        DescriptorProto response = DescriptorProto.newBuilder()
                .setName("DependentResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("ok")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_BOOL)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .build())
                .build();

        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("DependentService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("DependentMethod")
                        .setInputType(".dev.example.DependentRequest")
                        .setOutputType(".dev.example.DependentResponse")
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("dependent.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addDependency("dependency.proto")
                .addMessageType(request)
                .addMessageType(response)
                .addService(service)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[] {dependency});
    }

    // =========================================================================
    // Helper: Proto file and service descriptor supplier for reflection
    // =========================================================================

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
        public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
            return fileDescriptor;
        }

        @Override
        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
            return serviceDescriptor;
        }
    }

    // =========================================================================
    // Helper: Service info container
    // =========================================================================

    private static class ServiceInfo {
        final String serviceFqn;
        final FileDescriptor fileDescriptor;

        ServiceInfo(String serviceFqn, FileDescriptor fileDescriptor) {
            this.serviceFqn = serviceFqn;
            this.fileDescriptor = fileDescriptor;
        }
    }

    // =========================================================================
    // Helper: Get gRPC reflection service names
    // =========================================================================

    private static List<String> getGrpcReflectionServices() {
        List<String> reflectionServices = new ArrayList<>();
        reflectionServices.add("grpc.reflection.v1.ServerReflection");
        reflectionServices.add("grpc.reflection.v1alpha.ServerReflection");
        return reflectionServices;
    }

    // =========================================================================
    // Helper: Extract service name from fully-qualified name
    // =========================================================================

    private static String extractServiceName(String serviceFqn) {
        int lastDot = serviceFqn.lastIndexOf('.');
        if (lastDot == -1) {
            return serviceFqn;
        }
        return serviceFqn.substring(lastDot + 1);
    }
}
