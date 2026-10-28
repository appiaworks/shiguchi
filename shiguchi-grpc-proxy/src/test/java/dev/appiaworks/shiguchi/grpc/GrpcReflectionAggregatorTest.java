package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for GrpcReflectionAggregator.
 *
 * Strategy:
 * - Use a real GrpcRouteRegistry (no-arg constructor, trivial to use).
 * - Build real ServiceEndpointContext instances with hand-rolled proto descriptors
 *   (same technique as GrpcProxyHandlerRegistryTest).
 * - Test the bidi-streaming serverReflectionInfo() method by directly calling it
 *   and passing requests through a hand-written StreamObserver test double.
 * - No mocking of protobuf Descriptor types or StreamObserver — only real objects
 *   and a simple test-double for capturing responses.
 */
class GrpcReflectionAggregatorTest {

    private GrpcRouteRegistry registry;
    private GrpcReflectionAggregator aggregator;

    // Shared proto descriptors built once per test class load
    private static FileDescriptor testFileDescriptor;
    private static ServiceDescriptor testServiceDescriptor;

    private static FileDescriptor secondFileDescriptor;
    private static ServiceDescriptor secondServiceDescriptor;

    static {
        try {
            testFileDescriptor = buildTestFileDescriptor();
            testServiceDescriptor = testFileDescriptor.findServiceByName("GreetingService");

            secondFileDescriptor = buildSecondFileDescriptor();
            secondServiceDescriptor = secondFileDescriptor.findServiceByName("FarewellService");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeEach
    void setUp() {
        registry = new GrpcRouteRegistry();
        aggregator = new GrpcReflectionAggregator(registry);
    }

    private static FileDescriptorSet unchecked_buildTestFileDescriptorSet() {
        try {
            return buildTestFileDescriptorSet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FileDescriptorSet unchecked_buildSecondFileDescriptorSet() {
        try {
            return buildSecondFileDescriptorSet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FileDescriptorSet unchecked_buildTestFileDescriptorSetWithEnum() {
        try {
            return buildTestFileDescriptorSetWithEnum();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static FileDescriptorSet unchecked_buildSecondFileDescriptorSetWithCustomName(String filename) {
        try {
            return buildSecondFileDescriptorSetWithCustomName(filename);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // 1. LIST_SERVICES with two contexts having distinct services
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given two contexts each with one distinct service in serviceByFqn, when a LIST_SERVICES request is sent, should respond with a ListServicesResponse containing both fully-qualified service names")
    void GIVEN_twoContextsWithDistinctServices_WHEN_listServicesRequested_SHOULD_respondWithBothServiceNames() {
        ServiceEndpointContext ctx1 = buildContext(
                "backend-1",
                "dev.example.GreetingService",
                testServiceDescriptor,
                unchecked_buildTestFileDescriptorSet());
        ServiceEndpointContext ctx2 = buildContext(
                "backend-2",
                "dev.example.FarewellService",
                secondServiceDescriptor,
                unchecked_buildSecondFileDescriptorSet());

        registry.registerContext(ctx1);
        registry.registerContext(ctx2);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest listServicesRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setListServices("")
                .build();

        requestObserver.onNext(listServicesRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasListServicesResponse()).isTrue();
        List<String> serviceNames = response.getListServicesResponse().getServiceList().stream()
                .map(sr -> sr.getName())
                .toList();
        assertThat(serviceNames)
                .containsExactlyInAnyOrder("dev.example.GreetingService", "dev.example.FarewellService");
    }

    // -------------------------------------------------------------------------
    // 2. LIST_SERVICES with overlapping service FQNs (dedup test)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given two contexts whose serviceByFqn maps share one identical FQN key, when LIST_SERVICES is sent, should respond with that name appearing only ONCE")
    void GIVEN_twoContextsWithSharedServiceFqn_WHEN_listServicesRequested_SHOULD_respondWithDedupedServiceNames() {
        // Both contexts have the same service FQN
        ServiceEndpointContext ctx1 = buildContext(
                "backend-1",
                "dev.example.SharedService",
                testServiceDescriptor,
                unchecked_buildTestFileDescriptorSet());
        ServiceEndpointContext ctx2 = buildContext(
                "backend-2",
                "dev.example.SharedService",
                testServiceDescriptor,
                unchecked_buildTestFileDescriptorSet());

        registry.registerContext(ctx1);
        registry.registerContext(ctx2);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest listServicesRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setListServices("")
                .build();

        requestObserver.onNext(listServicesRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.getListServicesResponse().getServiceList())
                .hasSize(1)
                .extracting("name")
                .containsExactly("dev.example.SharedService");
    }

    // -------------------------------------------------------------------------
    // 3. FILE_CONTAINING_SYMBOL for a service FQN found in serviceByFqn
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given one context whose serviceByFqn contains a service and whose rawDescriptorSet has a file, when a FILE_CONTAINING_SYMBOL request for that service is sent, should respond with a FileDescriptorResponse containing the file")
    void GIVEN_contextWithServiceInServiceByFqn_WHEN_fileContainingSymbolRequested_SHOULD_respondWithDescriptorSet() {
        FileDescriptorSet descriptorSet = unchecked_buildTestFileDescriptorSet();
        ServiceEndpointContext ctx =
                buildContext("backend-1", "dev.example.GreetingService", testServiceDescriptor, descriptorSet);
        registry.registerContext(ctx);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest symbolRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setFileContainingSymbol("dev.example.GreetingService")
                .build();

        requestObserver.onNext(symbolRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasFileDescriptorResponse()).isTrue();
        assertThat(response.getFileDescriptorResponse().getFileDescriptorProtoList())
                .hasSize(1);

        // Verify the returned file descriptor proto is the one we put in
        try {
            FileDescriptorProto fdp = FileDescriptorProto.parseFrom(
                    response.getFileDescriptorResponse().getFileDescriptorProto(0));
            assertThat(fdp.getName()).isEqualTo("greeting.proto");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // 4. FILE_CONTAINING_SYMBOL for a message type (not service FQN)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a context whose rawDescriptorSet's file declares a message type not in serviceByFqn, when FILE_CONTAINING_SYMBOL is sent for that message, should find it via message-type scan and respond with descriptor set")
    void
            GIVEN_contextWithMessageTypeInDescriptorSet_WHEN_fileContainingSymbolRequestedForMessage_SHOULD_respondWithDescriptorSet() {
        FileDescriptorSet descriptorSet = unchecked_buildTestFileDescriptorSet();
        ServiceEndpointContext ctx =
                buildContext("backend-1", "dev.example.GreetingService", testServiceDescriptor, descriptorSet);
        registry.registerContext(ctx);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        // Request the message type, not the service
        ServerReflectionRequest symbolRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setFileContainingSymbol("dev.example.HelloRequest")
                .build();

        requestObserver.onNext(symbolRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasFileDescriptorResponse()).isTrue();
        assertThat(response.getFileDescriptorResponse().getFileDescriptorProtoList())
                .hasSize(1);

        try {
            FileDescriptorProto fdp = FileDescriptorProto.parseFrom(
                    response.getFileDescriptorResponse().getFileDescriptorProto(0));
            assertThat(fdp.getName()).isEqualTo("greeting.proto");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // 5. FILE_CONTAINING_SYMBOL for an enum type (not service FQN)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a context whose rawDescriptorSet's file declares an enum type, when FILE_CONTAINING_SYMBOL is sent for that enum, should find it via enum-type scan and respond with descriptor set")
    void
            GIVEN_contextWithEnumTypeInDescriptorSet_WHEN_fileContainingSymbolRequestedForEnum_SHOULD_respondWithDescriptorSet() {
        FileDescriptorSet descriptorSet = unchecked_buildTestFileDescriptorSetWithEnum();
        ServiceEndpointContext ctx =
                buildContext("backend-1", "dev.example.GreetingService", testServiceDescriptor, descriptorSet);
        registry.registerContext(ctx);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest symbolRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setFileContainingSymbol("dev.example.Greeting")
                .build();

        requestObserver.onNext(symbolRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasFileDescriptorResponse()).isTrue();
    }

    // -------------------------------------------------------------------------
    // 6. FILE_CONTAINING_SYMBOL for non-existent symbol returns NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given no context has the requested symbol anywhere, when FILE_CONTAINING_SYMBOL is sent, should respond with an ErrorResponse with code NOT_FOUND")
    void GIVEN_symbolNotFoundInAnyContext_WHEN_fileContainingSymbolRequested_SHOULD_respondWithNotFoundError() {
        ServiceEndpointContext ctx = buildContext(
                "backend-1",
                "dev.example.GreetingService",
                testServiceDescriptor,
                unchecked_buildTestFileDescriptorSet());
        registry.registerContext(ctx);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest symbolRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setFileContainingSymbol("does.not.Exist")
                .build();

        requestObserver.onNext(symbolRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasErrorResponse()).isTrue();
        assertThat(response.getErrorResponse().getErrorCode()).isEqualTo(io.grpc.Status.Code.NOT_FOUND.value());
        assertThat(response.getErrorResponse().getErrorMessage()).contains("Symbol not found");
    }

    // -------------------------------------------------------------------------
    // 7. FILE_BY_FILENAME with first-context-wins dedup
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given two contexts that both declare a file with the same filename but different content, when FILE_BY_FILENAME is sent, should respond with the file from the FIRST context registered")
    void GIVEN_twoContextsWithSameFilename_WHEN_fileByFilenameRequested_SHOULD_respondWithFirstContextFile() {
        // ctx1 has "shared.proto"
        FileDescriptorSet descriptorSet1 = unchecked_buildTestFileDescriptorSet();
        ServiceEndpointContext ctx1 =
                buildContext("backend-1", "dev.example.GreetingService", testServiceDescriptor, descriptorSet1);

        // ctx2 also has "shared.proto" (via second descriptor set, rewrite name to match)
        FileDescriptorSet descriptorSet2 = unchecked_buildSecondFileDescriptorSetWithCustomName("shared.proto");
        ServiceEndpointContext ctx2 =
                buildContext("backend-2", "dev.example.FarewellService", secondServiceDescriptor, descriptorSet2);

        // Register ctx1 first, so it should win
        registry.registerContext(ctx1);
        registry.registerContext(ctx2);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest filenameRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setFileByFilename("greeting.proto")
                .build();

        requestObserver.onNext(filenameRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasFileDescriptorResponse()).isTrue();

        try {
            FileDescriptorProto fdp = FileDescriptorProto.parseFrom(
                    response.getFileDescriptorResponse().getFileDescriptorProto(0));
            assertThat(fdp.getName()).isEqualTo("greeting.proto");
            assertThat(fdp.getPackage()).isEqualTo("dev.example");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // 8. FILE_BY_FILENAME for non-existent filename returns NOT_FOUND
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a filename not present in any context's descriptor set, when FILE_BY_FILENAME is sent, should respond with an ErrorResponse with code NOT_FOUND")
    void GIVEN_filenameNotFoundInAnyContext_WHEN_fileByFilenameRequested_SHOULD_respondWithNotFoundError() {
        ServiceEndpointContext ctx = buildContext(
                "backend-1",
                "dev.example.GreetingService",
                testServiceDescriptor,
                unchecked_buildTestFileDescriptorSet());
        registry.registerContext(ctx);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest filenameRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setFileByFilename("nonexistent.proto")
                .build();

        requestObserver.onNext(filenameRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasErrorResponse()).isTrue();
        assertThat(response.getErrorResponse().getErrorCode()).isEqualTo(io.grpc.Status.Code.NOT_FOUND.value());
        assertThat(response.getErrorResponse().getErrorMessage()).contains("File not found");
    }

    // -------------------------------------------------------------------------
    // 9. FILE_CONTAINING_SYMBOL with null rawDescriptorSet (should be skipped)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given a context whose getRawDescriptorSet() is null, when FILE_CONTAINING_SYMBOL is requested for anything, should skip that context and return NOT_FOUND (no NullPointerException)")
    void GIVEN_contextWithNullRawDescriptorSet_WHEN_fileContainingSymbolRequested_SHOULD_skipContextWithoutError() {
        // Build a context but don't set rawDescriptorSet (it stays null)
        ServiceDefinition config = new ServiceDefinition();
        config.setName("backend-1");
        config.setHost("localhost");
        config.setPort(9090);

        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);
        ctx.setServiceByFqn(Map.of("dev.example.GreetingService", testServiceDescriptor));
        ctx.setFileDescriptors(Map.of());
        // Deliberately NOT calling setRawDescriptorSet, so it stays null

        registry.registerContext(ctx);

        TestResponseObserver responseObserver = new TestResponseObserver();
        StreamObserver<ServerReflectionRequest> requestObserver = aggregator.serverReflectionInfo(responseObserver);

        ServerReflectionRequest symbolRequest = ServerReflectionRequest.newBuilder()
                .setHost("localhost")
                .setFileContainingSymbol("dev.example.GreetingService")
                .build();

        requestObserver.onNext(symbolRequest);

        assertThat(responseObserver.responses).hasSize(1);
        ServerReflectionResponse response = responseObserver.responses.get(0);
        assertThat(response.hasErrorResponse()).isTrue();
        assertThat(response.getErrorResponse().getErrorCode()).isEqualTo(io.grpc.Status.Code.NOT_FOUND.value());
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Builds a ServiceEndpointContext with the given service FQN and descriptor.
     */
    private static ServiceEndpointContext buildContext(
            String contextName, String serviceFqn, ServiceDescriptor sd, FileDescriptorSet descriptorSet) {
        ServiceDefinition config = new ServiceDefinition();
        config.setName(contextName);
        config.setHost("localhost");
        config.setPort(9090);

        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);
        ctx.setServiceByFqn(Map.of(serviceFqn, sd));

        // Collect all file descriptors from the descriptor set
        Map<String, FileDescriptor> fileDescriptors = new java.util.HashMap<>();
        if (descriptorSet != null) {
            for (FileDescriptorProto fdp : descriptorSet.getFileList()) {
                try {
                    FileDescriptor fd = FileDescriptor.buildFrom(fdp, new FileDescriptor[0]);
                    fileDescriptors.put(fd.getFullName(), fd);
                } catch (Exception e) {
                    // Ignore errors for dependency-free descriptors
                }
            }
        }

        ctx.setFileDescriptors(fileDescriptors);
        ctx.setRawDescriptorSet(descriptorSet);
        return ctx;
    }

    /**
     * Builds a minimal proto FileDescriptorSet containing:
     *   package dev.example;
     *   message HelloRequest  { string name = 1; }
     *   message HelloResponse { string greeting = 1; }
     *   service GreetingService { rpc SayHello(HelloRequest) returns (HelloResponse); }
     */
    private static FileDescriptorSet buildTestFileDescriptorSet() throws Exception {
        FileDescriptor fd = buildTestFileDescriptor();
        return FileDescriptorSet.newBuilder().addFile(fd.toProto()).build();
    }

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

        com.google.protobuf.DescriptorProtos.ServiceDescriptorProto greetingService =
                com.google.protobuf.DescriptorProtos.ServiceDescriptorProto.newBuilder()
                        .setName("GreetingService")
                        .addMethod(com.google.protobuf.DescriptorProtos.MethodDescriptorProto.newBuilder()
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
     * Builds a FileDescriptorSet with an enum type Greeting.
     */
    private static FileDescriptorSet buildTestFileDescriptorSetWithEnum() throws Exception {
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

        EnumDescriptorProto greetingEnum = EnumDescriptorProto.newBuilder()
                .setName("Greeting")
                .addValue(EnumValueDescriptorProto.newBuilder()
                        .setName("GREETING_UNKNOWN")
                        .setNumber(0)
                        .build())
                .addValue(EnumValueDescriptorProto.newBuilder()
                        .setName("GREETING_HELLO")
                        .setNumber(1)
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("greeting.proto")
                .setPackage("dev.example")
                .setSyntax("proto3")
                .addMessageType(helloRequest)
                .addMessageType(helloResponse)
                .addEnumType(greetingEnum)
                .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
        return FileDescriptorSet.newBuilder().addFile(fd.toProto()).build();
    }

    /**
     * Builds a second FileDescriptorSet for a different service.
     */
    private static FileDescriptorSet buildSecondFileDescriptorSet() throws Exception {
        FileDescriptor fd = buildSecondFileDescriptor();
        return FileDescriptorSet.newBuilder().addFile(fd.toProto()).build();
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

        com.google.protobuf.DescriptorProtos.ServiceDescriptorProto farewellService =
                com.google.protobuf.DescriptorProtos.ServiceDescriptorProto.newBuilder()
                        .setName("FarewellService")
                        .addMethod(com.google.protobuf.DescriptorProtos.MethodDescriptorProto.newBuilder()
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

    /**
     * Builds a second FileDescriptorSet with a custom filename (for dedup testing).
     */
    private static FileDescriptorSet buildSecondFileDescriptorSetWithCustomName(String filename) throws Exception {
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

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName(filename)
                .setPackage("dev.example.alt")
                .setSyntax("proto3")
                .addMessageType(goodbyeRequest)
                .addMessageType(goodbyeResponse)
                .build();

        FileDescriptor fd = FileDescriptor.buildFrom(fileProto, new FileDescriptor[0]);
        return FileDescriptorSet.newBuilder().addFile(fd.toProto()).build();
    }

    // =========================================================================
    // Test double for StreamObserver
    // =========================================================================

    /**
     * Simple test double for capturing responses from the aggregator.
     */
    private static class TestResponseObserver implements StreamObserver<ServerReflectionResponse> {
        List<ServerReflectionResponse> responses = new ArrayList<>();
        Throwable error;

        @Override
        public void onNext(ServerReflectionResponse response) {
            responses.add(response);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
        }

        @Override
        public void onCompleted() {
            // No-op for tests
        }
    }
}
