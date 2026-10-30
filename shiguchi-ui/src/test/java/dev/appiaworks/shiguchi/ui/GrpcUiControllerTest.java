package dev.appiaworks.shiguchi.ui;

import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.BadRequest;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCalls;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * BDD tests for {@link GrpcUiController}'s {@code invoke} and {@code invoke/stream} endpoints,
 * exercised end to end through a real in-process gRPC backend and a real {@link
 * ReactiveGrpcDispatcher} (same wiring approach as {@code MixedBackendMergeTest} in
 * shiguchi-rest), bound via {@link WebTestClient#bindToRouterFunction}. This proves the
 * controller's HTTP-facing contract — always-200 unary responses with an embedded gRPC status,
 * 404 for an unknown service, and SSE framing for server-streaming — rather than mocking the
 * dispatcher, which is a concrete class with real dispatch logic worth exercising for real.
 */
@DisplayName("GrpcUiController invoke and invoke/stream endpoints")
class GrpcUiControllerTest {

    private static Server grpcServer;
    private static ManagedChannel grpcChannel;

    private WebTestClient client;

    @BeforeAll
    static void startGrpcBackend() throws Exception {
        FileDescriptor file = echoServiceFile();
        Descriptor echoRequest = file.findMessageTypeByName("EchoRequest");
        Descriptor echoResponse = file.findMessageTypeByName("EchoResponse");

        var unaryMethod = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("test.ui.EchoService/Echo")
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(echoRequest).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(echoResponse).build()))
                .build();
        var streamMethod = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName("test.ui.EchoService/EchoStream")
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(echoRequest).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(echoResponse).build()))
                .build();

        var collectMethod = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.CLIENT_STREAMING)
                .setFullMethodName("test.ui.EchoService/EchoCollect")
                .setRequestMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(echoRequest).build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(echoResponse).build()))
                .build();

        var service = ServerServiceDefinition.builder("test.ui.EchoService")
                .addMethod(
                        unaryMethod,
                        ServerCalls.asyncUnaryCall(
                                (DynamicMessage req, io.grpc.stub.StreamObserver<DynamicMessage> obs) -> {
                                    String text = (String) req.getField(echoRequest.findFieldByName("text"));
                                    if ("FAIL".equals(text)) {
                                        obs.onError(invalidText());
                                        return;
                                    }
                                    obs.onNext(DynamicMessage.newBuilder(echoResponse)
                                            .setField(echoResponse.findFieldByName("text"), text)
                                            .build());
                                    obs.onCompleted();
                                }))
                .addMethod(
                        collectMethod,
                        ServerCalls.asyncClientStreamingCall((io.grpc.stub.StreamObserver<DynamicMessage> obs) ->
                                new io.grpc.stub.StreamObserver<DynamicMessage>() {
                                    private final StringBuilder joined = new StringBuilder();

                                    @Override
                                    public void onNext(DynamicMessage req) {
                                        String text = (String) req.getField(echoRequest.findFieldByName("text"));
                                        if (joined.length() > 0) joined.append(',');
                                        joined.append(text);
                                    }

                                    @Override
                                    public void onError(Throwable t) {}

                                    @Override
                                    public void onCompleted() {
                                        obs.onNext(DynamicMessage.newBuilder(echoResponse)
                                                .setField(echoResponse.findFieldByName("text"), joined.toString())
                                                .build());
                                        obs.onCompleted();
                                    }
                                }))
                .addMethod(
                        streamMethod,
                        ServerCalls.asyncServerStreamingCall(
                                (DynamicMessage req, io.grpc.stub.StreamObserver<DynamicMessage> obs) -> {
                                    String text = (String) req.getField(echoRequest.findFieldByName("text"));
                                    for (int i = 0; i < 3; i++) {
                                        obs.onNext(DynamicMessage.newBuilder(echoResponse)
                                                .setField(echoResponse.findFieldByName("text"), text + "-" + i)
                                                .build());
                                        if ("FAIL".equals(text)) {
                                            obs.onError(invalidText());
                                            return;
                                        }
                                    }
                                    obs.onCompleted();
                                }))
                .build();

        String name = InProcessServerBuilder.generateName();
        grpcServer = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        grpcChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterAll
    static void stopGrpcBackend() {
        grpcChannel.shutdownNow();
        grpcServer.shutdownNow();
    }

    @BeforeEach
    void wireController() throws Exception {
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        var props = new GatewayProperties();
        props.getClient().setDefaultDeadline(Duration.ofSeconds(10));
        var dispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);

        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(echoContext(grpcChannel));

        var schemaBuilder = new GrpcUiSchemaBuilder(registry);
        schemaBuilder.rebuild();
        var metadataPopulator = new GrpcMetadataPopulator(props);

        var service = new GrpcUiService(
                schemaBuilder,
                dispatcher,
                registry,
                props,
                "/swagger-ui.html",
                "/graphiql",
                false,
                "/push",
                "/actuator");
        var controller = new GrpcUiController(service, metadataPopulator);

        var routerFunction = RouterFunctions.route()
                .POST("/api/invoke", controller::handleInvoke)
                .POST("/api/invoke/stream", controller::handleStream)
                .GET("/api/schema", controller::handleGetSchema)
                .build();

        client = WebTestClient.bindToRouterFunction(routerFunction)
                .configureClient()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    @AfterEach
    void noop() {
        // per-test WebTestClient instances need no explicit teardown
    }

    @Test
    @DisplayName(
            "GIVEN a unary route WHEN invoking it SHOULD return the dispatcher's JSON wrapped in an OK InvokeResponse")
    void given_unaryRoute_when_invoking_should_returnOkInvokeResponse() {
        client.post()
                .uri("/api/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.EchoService", "Echo", "{\"text\":\"hello\"}"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.grpcStatus")
                .isEqualTo("OK")
                .jsonPath("$.errorMessage")
                .doesNotExist()
                .jsonPath("$.response")
                .value(org.hamcrest.Matchers.containsString("hello"));
    }

    @Test
    @DisplayName(
            "GIVEN an unknown service name WHEN invoking it SHOULD return HTTP 200 with a NOT_FOUND-shaped InvokeResponse")
    void given_unknownService_when_invoking_should_returnNotFoundShapedResponse() {
        client.post()
                .uri("/api/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.DoesNotExist", "Whatever", "{}"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.grpcStatus")
                .isEqualTo("NOT_FOUND")
                .jsonPath("$.response")
                .doesNotExist()
                .jsonPath("$.errorMessage")
                .value(org.hamcrest.Matchers.containsString("test.ui.DoesNotExist"));
    }

    @Test
    @DisplayName(
            "GIVEN the backend raises a StatusRuntimeException WHEN invoking SHOULD map it to its gRPC status code and message")
    void given_backendStatusException_when_invoking_should_mapToGrpcStatusInResponse() {
        client.post()
                .uri("/api/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.EchoService", "Echo", "{\"text\":\"FAIL\"}"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.grpcStatus")
                .isEqualTo("INVALID_ARGUMENT")
                .jsonPath("$.errorMessage")
                .isEqualTo("text must not be FAIL")
                .jsonPath("$.response")
                .doesNotExist()
                .jsonPath("$.errorDetails[0]['@type']")
                .isEqualTo("type.googleapis.com/google.rpc.BadRequest")
                .jsonPath("$.errorDetails[0].fieldViolations[0].field")
                .isEqualTo("text");
    }

    @Test
    @DisplayName("GIVEN a unary call that succeeds WHEN invoking SHOULD not carry an errorDetails member at all")
    void given_successfulCall_when_invoking_should_omitErrorDetails() {
        client.post()
                .uri("/api/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.EchoService", "Echo", "{\"text\":\"fine\"}"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.errorDetails")
                .doesNotExist();
    }

    @Test
    @DisplayName(
            "GIVEN the backend fails mid-stream with a BadRequest detail WHEN invoking the stream endpoint SHOULD emit the chunks so far, then an error event carrying code, message and details, then done")
    void given_backendFailsMidStream_when_invokingStream_should_emitErrorEventWithDetails() {
        Flux<String> body = client.post()
                .uri("/api/invoke/stream")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.EchoService", "EchoStream", "{\"text\":\"FAIL\"}"))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(body)
                .expectNextMatches(chunk -> chunk.contains("FAIL-0"))
                .expectNextMatches(chunk -> chunk.contains("\"code\":\"INVALID_ARGUMENT\"")
                        && chunk.contains("\"message\":\"text must not be FAIL\"")
                        && chunk.contains("type.googleapis.com/google.rpc.BadRequest")
                        && chunk.contains("\"field\":\"text\""))
                .expectNextMatches(chunk -> chunk.contains("durationMs"))
                .expectComplete()
                .verify(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("GIVEN a unary invoke call WHEN it completes SHOULD report a non-negative duration")
    void given_unaryInvoke_when_completing_should_reportDuration() {
        client.post()
                .uri("/api/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.EchoService", "Echo", "{\"text\":\"timed\"}"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.durationMs")
                .value(org.hamcrest.Matchers.greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName(
            "GIVEN a server-streaming route WHEN invoking the stream endpoint SHOULD emit one SSE data event per response chunk followed by a done event")
    void given_serverStreamingRoute_when_invokingStream_should_emitDataEventsThenDone() {
        Flux<String> body = client.post()
                .uri("/api/invoke/stream")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.EchoService", "EchoStream", "{\"text\":\"chunk\"}"))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(String.class)
                .getResponseBody();

        StepVerifier.create(body)
                .expectNextMatches(chunk -> chunk.contains("chunk-0"))
                .expectNextMatches(chunk -> chunk.contains("chunk-1"))
                .expectNextMatches(chunk -> chunk.contains("chunk-2"))
                .expectNextMatches(chunk -> chunk.contains("durationMs"))
                .expectComplete()
                .verify(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("GIVEN an unknown service name WHEN invoking the stream endpoint SHOULD return HTTP 404")
    void given_unknownService_when_invokingStream_should_return404() {
        client.post()
                .uri("/api/invoke/stream")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.DoesNotExist", "Whatever", "{}"))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    // -------------------------------------------------------------------------

    /** INVALID_ARGUMENT with a google.rpc.BadRequest detail in the trailers, as protovalidate-style backends send. */
    private static StatusRuntimeException invalidText() {
        BadRequest badRequest = BadRequest.newBuilder()
                .addFieldViolations(
                        BadRequest.FieldViolation.newBuilder().setField("text").setDescription("must not be FAIL"))
                .build();
        return StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage("text must not be FAIL")
                .addDetails(Any.pack(badRequest))
                .build());
    }

    @Test
    @DisplayName(
            "GIVEN a client-streaming method WHEN the console sends several payloads SHOULD deliver them all on one call and answer once")
    void given_clientStreamingMethod_when_severalPayloads_should_sendAllAndAnswerOnce() {
        String body = "{\"serviceName\":\"test.ui.EchoService\",\"methodName\":\"EchoCollect\","
                + "\"payload\":\"{\\\"text\\\":\\\"a\\\"}\","
                + "\"payloads\":[\"{\\\"text\\\":\\\"a\\\"}\",\"{\\\"text\\\":\\\"b\\\"}\","
                + "\"{\\\"text\\\":\\\"c\\\"}\"]}";

        client.post()
                .uri("/api/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.grpcStatus")
                .isEqualTo("OK")
                .jsonPath("$.response")
                .value(org.hamcrest.Matchers.containsString("a,b,c"));
    }

    @Test
    @DisplayName(
            "GIVEN a client-streaming method WHEN only the single payload is sent SHOULD still deliver it, so an older console keeps working")
    void given_clientStreamingMethod_when_onlyPayload_should_sendThatOne() {
        client.post()
                .uri("/api/invoke")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(invokeRequestJson("test.ui.EchoService", "EchoCollect", "{\"text\":\"solo\"}"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.grpcStatus")
                .isEqualTo("OK")
                .jsonPath("$.response")
                .value(org.hamcrest.Matchers.containsString("solo"));
    }

    private static String invokeRequestJson(String serviceName, String methodName, String payload) {
        String escapedPayload = payload.replace("\"", "\\\"");
        return "{\"serviceName\":\"" + serviceName + "\",\"methodName\":\"" + methodName + "\",\"payload\":\""
                + escapedPayload + "\"}";
    }

    private static ServiceEndpointContext echoContext(ManagedChannel channel) throws Exception {
        var config = new ServiceDefinition();
        config.setName("echo-svc");
        var ctx = new ServiceEndpointContext(config, channel);
        FileDescriptor file = echoServiceFile();
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        ServiceDescriptor service = file.findServiceByName("EchoService");
        ctx.setServiceByFqn(Map.of(service.getFullName(), service));
        ctx.setMethodByKey(Map.of(
                service.getFullName() + "/Echo", service.findMethodByName("Echo"),
                service.getFullName() + "/EchoStream", service.findMethodByName("EchoStream"),
                service.getFullName() + "/EchoCollect", service.findMethodByName("EchoCollect")));
        JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
        var factory = new DynamicSerializerFactory();
        for (Descriptor d : file.getMessageTypes()) {
            types.add(d);
            ctx.putSerializer(d, factory.compileDescriptor(d));
        }
        ctx.setTypeRegistry(types.build());
        return ctx;
    }

    private static FileDescriptor echoServiceFile() throws Exception {
        DescriptorProto echoRequest = DescriptorProto.newBuilder()
                .setName("EchoRequest")
                .addField(scalar("text", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto echoResponse = DescriptorProto.newBuilder()
                .setName("EchoResponse")
                .addField(scalar("text", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        ServiceDescriptorProto service = ServiceDescriptorProto.newBuilder()
                .setName("EchoService")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("Echo")
                        .setInputType(".test.ui.EchoRequest")
                        .setOutputType(".test.ui.EchoResponse"))
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("EchoStream")
                        .setInputType(".test.ui.EchoRequest")
                        .setOutputType(".test.ui.EchoResponse")
                        .setServerStreaming(true))
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("EchoCollect")
                        .setInputType(".test.ui.EchoRequest")
                        .setOutputType(".test.ui.EchoResponse")
                        .setClientStreaming(true))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("test_ui_echo.proto")
                .setPackage("test.ui")
                .setSyntax("proto3")
                .addMessageType(echoRequest)
                .addMessageType(echoResponse)
                .addService(service)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]);
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
