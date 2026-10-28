package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.Message;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessage;
import dev.appiaworks.shiguchi.proto.cursus.ClientMessageResult;
import dev.appiaworks.shiguchi.proto.cursus.ConnectDecision;
import dev.appiaworks.shiguchi.proto.cursus.ConnectionInfo;
import dev.appiaworks.shiguchi.proto.cursus.PushHooksProto;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeDecision;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeHook;
import dev.appiaworks.shiguchi.proto.cursus.TopicActivity;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@DisplayName("gRPC push hook: notifications forwarded to a served backend service")
class GrpcPushHookTest {

    private static final String SERVICE = "hooks.Backend";
    private static final String EMPTY = ".google.protobuf.Empty";
    private static final String TOPIC_ACTIVITY = ".shiguchi.cursus.v1.TopicActivity";
    private static final String CONNECTION_INFO = ".shiguchi.cursus.v1.ConnectionInfo";

    private final RecordingCaller caller = new RecordingCaller();

    @Test
    @DisplayName(
            "GIVEN a configured service the gateway does not serve WHEN the hook is built SHOULD fail naming hooks.grpc-service")
    void given_unservedService_when_hookBuilt_should_failNamingHooksGrpcService() {
        GrpcRouteRegistry registry = registryServing(backendFile());

        assertThatThrownBy(() -> new GrpcPushHook(registry, "hooks.Missing", caller))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hooks.grpc-service");
    }

    @Test
    @DisplayName(
            "GIVEN a served backend implementing OnTopicActive WHEN a topic becomes active SHOULD call OnTopicActive with the topic in the request")
    void given_servedBackend_when_topicActive_should_callOnTopicActiveWithTopic() {
        GrpcPushHook hook = new GrpcPushHook(registryServing(backendFile()), SERVICE, caller);
        TopicActivity activity = TopicActivity.newBuilder()
                .setTopic("orders:42")
                .setInstance("gw-1")
                .setSubscribers(1)
                .build();

        hook.onTopicActive(activity).block();

        assertThat(caller.calls).hasSize(1);
        RecordingCaller.Call call = caller.calls.get(0);
        assertThat(call.service()).isEqualTo(SERVICE);
        assertThat(call.method()).isEqualTo("OnTopicActive");
        Descriptors.FieldDescriptor topic =
                call.request().getDescriptorForType().findFieldByName("topic");
        assertThat(call.request().getField(topic)).isEqualTo("orders:42");
    }

    @Test
    @DisplayName(
            "GIVEN a backend that does not implement OnTopicIdle WHEN a topic goes idle SHOULD complete without calling the backend")
    void given_methodNotImplemented_when_topicIdle_should_completeWithoutCalling() {
        GrpcPushHook hook = new GrpcPushHook(registryServing(backendFile()), SERVICE, caller);

        hook.onTopicIdle(TopicActivity.newBuilder().setTopic("orders:42").build())
                .block();

        assertThat(caller.calls).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a service name with surrounding whitespace WHEN the hook is built SHOULD report the trimmed service name")
    void given_paddedServiceName_when_hookBuilt_should_reportTrimmedName() {
        GrpcPushHook hook = new GrpcPushHook(registryServing(backendFile()), "  " + SERVICE + "  ", caller);

        assertThat(hook.serviceFqn()).isEqualTo(SERVICE);
    }

    @Test
    @DisplayName(
            "GIVEN a served backend implementing OnConnect WHEN a connection opens SHOULD call OnConnect with the connection's transport")
    void given_servedBackend_when_connectionOpens_should_callOnConnectWithTransport() {
        GrpcPushHook hook = new GrpcPushHook(registryServing(backendFile()), SERVICE, caller);

        hook.decideConnect(ConnectionInfo.newBuilder()
                        .setId("c-1")
                        .setTransport("ws")
                        .build())
                .block();

        assertThat(caller.calls).hasSize(1);
        RecordingCaller.Call call = caller.calls.get(0);
        assertThat(call.method()).isEqualTo("OnConnect");
        Descriptors.FieldDescriptor transport =
                call.request().getDescriptorForType().findFieldByName("transport");
        assertThat(call.request().getField(transport)).isEqualTo("ws");
    }

    @Test
    @DisplayName(
            "GIVEN a backend implementing OnConnect WHEN the OnConnect notification is delivered SHOULD not call the backend, since decideConnect already does")
    void given_servedBackend_when_connectNotification_should_notCallBackend() {
        GrpcPushHook hook = new GrpcPushHook(registryServing(backendFile()), SERVICE, caller);

        hook.onConnect(ConnectionInfo.newBuilder().setId("c-1").build()).block();

        assertThat(caller.calls).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a backend that answers OnConnect with a reject and topics WHEN the connect is decided SHOULD parse the reply as a ConnectDecision")
    void given_backendRejects_when_connectDecided_should_parseConnectDecision() {
        ScriptedCaller scripted = new ScriptedCaller();
        scripted.replies.put(
                "OnConnect",
                ConnectDecision.newBuilder()
                        .setReject(true)
                        .setReason("banned")
                        .addSubscribe("orders:1")
                        .addSubscribe("orders:2")
                        .build());
        GrpcPushHook hook = new GrpcPushHook(registryServing(decisionsFile()), SERVICE, scripted);

        ConnectDecision decision = hook.decideConnect(
                        ConnectionInfo.newBuilder().setId("c-1").build())
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getReject()).isTrue();
        assertThat(decision.getReason()).isEqualTo("banned");
        assertThat(decision.getSubscribeList()).containsExactly("orders:1", "orders:2");
    }

    @Test
    @DisplayName(
            "GIVEN a backend that still answers OnConnect with Empty WHEN the connect is decided SHOULD read it as the default decision, an allow")
    void given_backendAnswersEmpty_when_connectDecided_should_allow() {
        GrpcPushHook hook = new GrpcPushHook(registryServing(backendFile()), SERVICE, caller);

        ConnectDecision decision = hook.decideConnect(
                        ConnectionInfo.newBuilder().setId("c-1").build())
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getReject()).isFalse();
        assertThat(decision.getSubscribeList()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a backend that rewrites the topics WHEN the subscribe is decided SHOULD send the requested topics and return the SubscribeDecision")
    void given_backendRewrites_when_subscribeDecided_should_returnRewrittenTopics() {
        ScriptedCaller scripted = new ScriptedCaller();
        scripted.replies.put(
                "OnSubscribe",
                SubscribeDecision.newBuilder().addTopics("orders:2").build());
        GrpcPushHook hook = new GrpcPushHook(registryServing(decisionsFile()), SERVICE, scripted);

        SubscribeDecision decision = hook.decideSubscribe(SubscribeHook.newBuilder()
                        .setConnection(ConnectionInfo.newBuilder().setId("c-1"))
                        .addTopics("orders:1")
                        .build())
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.getTopicsList()).containsExactly("orders:2");
        DynamicMessage sent = scripted.calls.get(0).request();
        Descriptors.FieldDescriptor topics = sent.getDescriptorForType().findFieldByName("topics");
        assertThat(sent.getField(topics)).isEqualTo(List.of("orders:1"));
    }

    @Test
    @DisplayName("GIVEN a backend that drops a client message WHEN the message is decided SHOULD return a DROP result")
    void given_backendDrops_when_clientMessageDecided_should_returnDrop() {
        ScriptedCaller scripted = new ScriptedCaller();
        scripted.replies.put(
                "OnClientMessage",
                ClientMessageResult.newBuilder()
                        .setAction(ClientMessageResult.Action.DROP)
                        .setReason("noise")
                        .build());
        GrpcPushHook hook = new GrpcPushHook(registryServing(decisionsFile()), SERVICE, scripted);

        ClientMessageResult result = hook.onClientMessage(ClientMessage.newBuilder()
                        .setTopic("chat:room1")
                        .setPayload(ByteString.copyFromUtf8("{}"))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getAction()).isEqualTo(ClientMessageResult.Action.DROP);
        assertThat(result.getReason()).isEqualTo("noise");
    }

    @Test
    @DisplayName(
            "GIVEN a backend that answers UNIMPLEMENTED to OnSubscribe WHEN the subscribe is decided twice SHOULD allow both times and call the backend only once")
    void given_unimplementedMethod_when_decidedTwice_should_rememberAndNotCallAgain() {
        ScriptedCaller scripted = new ScriptedCaller();
        scripted.failures.put("OnSubscribe", Status.UNIMPLEMENTED.asRuntimeException());
        GrpcPushHook hook = new GrpcPushHook(registryServing(decisionsFile()), SERVICE, scripted);
        SubscribeHook request = SubscribeHook.newBuilder().addTopics("orders:1").build();

        assertThat(hook.decideSubscribe(request).block()).isNull();
        assertThat(hook.decideSubscribe(request).block()).isNull();

        assertThat(scripted.calls).hasSize(1);
    }

    // ---- helpers ----

    /** A service {@code hooks.Backend} whose OnConnect, OnSubscribe and OnClientMessage answer the decision messages. */
    private static FileDescriptor decisionsFile() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("hooks/decisions.proto")
                .setSyntax("proto3")
                .setPackage("hooks")
                .addDependency(PushHooksProto.getDescriptor().getName())
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Backend")
                        .addMethod(decision("OnConnect", CONNECTION_INFO, ".shiguchi.cursus.v1.ConnectDecision"))
                        .addMethod(decision(
                                "OnSubscribe",
                                ".shiguchi.cursus.v1.SubscribeHook",
                                ".shiguchi.cursus.v1.SubscribeDecision"))
                        .addMethod(decision(
                                "OnClientMessage",
                                ".shiguchi.cursus.v1.ClientMessage",
                                ".shiguchi.cursus.v1.ClientMessageResult")))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {PushHooksProto.getDescriptor()});
        } catch (Descriptors.DescriptorValidationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto decision(String name, String input, String output) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(input)
                .setOutputType(output)
                .build();
    }

    /** Answers each method with the message scripted for it (as the method's output type), or fails it. */
    static final class ScriptedCaller implements GrpcPushHook.Caller {

        final Map<String, Message> replies = new java.util.HashMap<>();
        final Map<String, RuntimeException> failures = new java.util.HashMap<>();
        final List<RecordingCaller.Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public Mono<DynamicMessage> call(
                ServiceEndpointContext ctx, String serviceFqn, String methodName, DynamicMessage request) {
            calls.add(new RecordingCaller.Call(serviceFqn, methodName, request));
            if (failures.containsKey(methodName)) return Mono.error(failures.get(methodName));
            Descriptors.MethodDescriptor method =
                    ctx.lookupMethod(serviceFqn, methodName).orElseThrow();
            Message reply = replies.get(methodName);
            if (reply == null) return Mono.just(DynamicMessage.getDefaultInstance(method.getOutputType()));
            try {
                return Mono.just(DynamicMessage.parseFrom(method.getOutputType(), reply.toByteArray()));
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                return Mono.error(e);
            }
        }
    }

    /** A service {@code hooks.Backend} with OnTopicActive and OnConnect only: the rest are not implemented. */
    private static FileDescriptor backendFile() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("hooks/backend.proto")
                .setSyntax("proto3")
                .setPackage("hooks")
                .addDependency(PushHooksProto.getDescriptor().getName())
                .addDependency(EmptyProto.getDescriptor().getName())
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Backend")
                        .addMethod(method("OnTopicActive", TOPIC_ACTIVITY))
                        .addMethod(method("OnConnect", CONNECTION_INFO)))
                .build();
        try {
            return FileDescriptor.buildFrom(
                    proto, new FileDescriptor[] {PushHooksProto.getDescriptor(), EmptyProto.getDescriptor()});
        } catch (Descriptors.DescriptorValidationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto method(String name, String input) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(input)
                .setOutputType(EMPTY)
                .build();
    }

    private static GrpcRouteRegistry registryServing(FileDescriptor file) {
        ServiceDefinition config = new ServiceDefinition();
        config.setName("hooks");
        ServiceEndpointContext context = new ServiceEndpointContext(config, null);
        context.setFileDescriptors(Map.of(file.getName(), file));
        java.util.Map<String, com.google.protobuf.Descriptors.ServiceDescriptor> services = new java.util.HashMap<>();
        java.util.Map<String, com.google.protobuf.Descriptors.MethodDescriptor> methods = new java.util.HashMap<>();
        for (com.google.protobuf.Descriptors.ServiceDescriptor s : file.getServices()) {
            services.put(s.getFullName(), s);
            for (com.google.protobuf.Descriptors.MethodDescriptor m : s.getMethods()) {
                methods.put(s.getFullName() + "/" + m.getName(), m);
            }
        }
        context.setServiceByFqn(services);
        context.setMethodByKey(methods);
        GrpcRouteRegistry registry = new GrpcRouteRegistry();
        registry.registerContext(context);
        return registry;
    }

    /** Records each unary call the hook makes and answers with the output type's default message. */
    static final class RecordingCaller implements GrpcPushHook.Caller {

        record Call(String service, String method, DynamicMessage request) {}

        final List<Call> calls = new CopyOnWriteArrayList<>();

        @Override
        public Mono<DynamicMessage> call(
                ServiceEndpointContext ctx, String serviceFqn, String methodName, DynamicMessage request) {
            calls.add(new Call(serviceFqn, methodName, request));
            Descriptors.MethodDescriptor method =
                    ctx.lookupMethod(serviceFqn, methodName).orElseThrow();
            return Mono.just(DynamicMessage.getDefaultInstance(method.getOutputType()));
        }
    }
}
