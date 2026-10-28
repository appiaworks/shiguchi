package dev.appiaworks.shiguchi.proto.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import dev.appiaworks.shiguchi.proto.api.TopicProto;
import dev.appiaworks.shiguchi.proto.api.UpstreamTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped push service against what the gateway's push module and its documentation rely on:
 * the service and method names the in-process registration and the proxy route by, the HTTP
 * bindings the transcoding mounts under the push path, and the payload shape.
 */
@DisplayName("Push proto contract")
class PushProtoContractTest {

    private static final ServiceDescriptor SERVICE = PushProto.getDescriptor().findServiceByName("PushService");

    @Test
    @DisplayName("GIVEN the shipped service WHEN its name is read SHOULD be shiguchi.cursus.v1.PushService")
    void given_service_when_named_should_matchTheRegistration() {
        assertThat(SERVICE.getFullName()).isEqualTo("shiguchi.cursus.v1.PushService");
        assertThat(PushServiceGrpc.SERVICE_NAME).isEqualTo(SERVICE.getFullName());
    }

    @Test
    @DisplayName("GIVEN Publish WHEN its binding is read SHOULD be a unary POST /v1/publish with the whole body")
    void given_publish_when_bindingRead_should_postV1Publish() {
        MethodDescriptor publish = SERVICE.findMethodByName("Publish");
        assertThat(publish.isServerStreaming()).isFalse();
        assertThat(publish.isClientStreaming()).isFalse();
        HttpRule rule = publish.getOptions().getExtension(AnnotationsProto.http);
        assertThat(rule.getPost()).isEqualTo("/v1/publish");
        assertThat(rule.getBody()).isEqualTo("*");
    }

    @Test
    @DisplayName("GIVEN Subscribe WHEN its binding is read SHOULD be a server stream on GET /v1/subscribe")
    void given_subscribe_when_bindingRead_should_getV1Subscribe() {
        MethodDescriptor subscribe = SERVICE.findMethodByName("Subscribe");
        assertThat(subscribe.isServerStreaming()).isTrue();
        assertThat(subscribe.isClientStreaming()).isFalse();
        assertThat(subscribe.getOptions().getExtension(AnnotationsProto.http).getGet())
                .isEqualTo("/v1/subscribe");
    }

    @Test
    @DisplayName(
            "GIVEN PushEvent and PublishRequest WHEN their payload is read SHOULD be a oneof of json (Value) and message (Any)")
    void given_messages_when_payloadRead_should_offerJsonOrAny() {
        for (var message : new com.google.protobuf.Descriptors.Descriptor[] {
            PushEvent.getDescriptor(), PublishRequest.getDescriptor()
        }) {
            var oneof = message.getOneofs().stream()
                    .filter(o -> o.getName().equals("payload"))
                    .findFirst()
                    .orElseThrow();
            assertThat(oneof.getFields()).extracting(f -> f.getName()).containsExactly("json", "message");
            assertThat(message.findFieldByName("json").getMessageType().getFullName())
                    .isEqualTo("google.protobuf.Value");
            assertThat(message.findFieldByName("message").getMessageType().getFullName())
                    .isEqualTo("google.protobuf.Any");
        }
        assertThat(PushEvent.getDescriptor()
                        .findFieldByName("time")
                        .getMessageType()
                        .getFullName())
                .isEqualTo("google.protobuf.Timestamp");
        assertThat(SubscribeRequest.getDescriptor().findFieldByName("after")).isNotNull();
        assertThat(Qos.AT_MOST_ONCE.getNumber()).isEqualTo(1);
        assertThat(Qos.AT_LEAST_ONCE.getNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN the topic option WHEN read SHOULD extend MethodOptions at 50200 and UpstreamTopic SHOULD carry pattern 1, mode 2, event_id_field 3 and SHARED_UPSTREAM 0")
    void given_topicOption_when_read_should_extendMethodOptionsWithPatternAndMode() {
        assertThat(TopicProto.topic.getDescriptor().getContainingType().getFullName())
                .isEqualTo("google.protobuf.MethodOptions");
        assertThat(TopicProto.topic.getDescriptor().getNumber()).isEqualTo(50200);

        var upstreamTopic = UpstreamTopic.getDescriptor();
        assertThat(upstreamTopic.findFieldByName("pattern").getNumber()).isEqualTo(1);
        assertThat(upstreamTopic.findFieldByName("mode").getNumber()).isEqualTo(2);
        assertThat(upstreamTopic.findFieldByName("event_id_field").getNumber()).isEqualTo(3);
        assertThat(UpstreamTopic.Mode.SHARED_UPSTREAM.getNumber()).isZero();
    }

    @Test
    @DisplayName(
            "GIVEN HasSubscribers and GetSubscribers WHEN their bindings are read SHOULD be POST /v1/has-subscribers and GET /v1/subscribers with pinned field numbers")
    void given_presence_when_bindingsRead_should_matchTheContract() {
        MethodDescriptor has = SERVICE.findMethodByName("HasSubscribers");
        HttpRule hasRule = has.getOptions().getExtension(AnnotationsProto.http);
        assertThat(hasRule.getPost()).isEqualTo("/v1/has-subscribers");
        assertThat(hasRule.getBody()).isEqualTo("*");
        MethodDescriptor get = SERVICE.findMethodByName("GetSubscribers");
        assertThat(get.getOptions().getExtension(AnnotationsProto.http).getGet())
                .isEqualTo("/v1/subscribers");
        assertThat(GetSubscribersRequest.getDescriptor()
                        .findFieldByName("topic")
                        .getNumber())
                .isEqualTo(1);
        assertThat(GetSubscribersRequest.getDescriptor()
                        .findFieldByName("page_size")
                        .getNumber())
                .isEqualTo(2);
        assertThat(GetSubscribersRequest.getDescriptor()
                        .findFieldByName("page_token")
                        .getNumber())
                .isEqualTo(3);
        assertThat(GetSubscribersResponse.getDescriptor()
                        .findFieldByName("count")
                        .getNumber())
                .isEqualTo(1);
        assertThat(GetSubscribersResponse.getDescriptor()
                        .findFieldByName("subscribers")
                        .getNumber())
                .isEqualTo(2);
        assertThat(GetSubscribersResponse.getDescriptor()
                        .findFieldByName("next_page_token")
                        .getNumber())
                .isEqualTo(3);
        assertThat(Subscriber.getDescriptor().findFieldByName("connection_id").getNumber())
                .isEqualTo(1);
        assertThat(Subscriber.getDescriptor().findFieldByName("device_id").getNumber())
                .isEqualTo(3);
        assertThat(Subscriber.getDescriptor().findFieldByName("since").getNumber())
                .isEqualTo(6);
    }

    @Test
    @DisplayName(
            "GIVEN Ack WHEN its binding is read SHOULD be a unary POST /v1/ack with the whole body, and SubscribeRequest SHOULD carry session at 4")
    void given_ack_when_bindingRead_should_postV1Ack() {
        MethodDescriptor ack = SERVICE.findMethodByName("Ack");
        assertThat(ack.isServerStreaming()).isFalse();
        assertThat(ack.isClientStreaming()).isFalse();
        HttpRule rule = ack.getOptions().getExtension(AnnotationsProto.http);
        assertThat(rule.getPost()).isEqualTo("/v1/ack");
        assertThat(rule.getBody()).isEqualTo("*");
        assertThat(AckRequest.getDescriptor().findFieldByName("topic").getNumber())
                .isEqualTo(1);
        assertThat(AckRequest.getDescriptor().findFieldByName("id").getNumber()).isEqualTo(2);
        assertThat(AckRequest.getDescriptor().findFieldByName("session").getNumber())
                .isEqualTo(3);
        assertThat(SubscribeRequest.getDescriptor().findFieldByName("session").getNumber())
                .isEqualTo(4);
        assertThat(SubscribeRequest.getDescriptor().findFieldByName("group").getNumber())
                .isEqualTo(5);
        assertThat(SubscribeRequest.getDescriptor().findFieldByName("member").getNumber())
                .isEqualTo(6);
        assertThat(AckRequest.getDescriptor().findFieldByName("group").getNumber())
                .isEqualTo(4);
        assertThat(AckRequest.getDescriptor().findFieldByName("member").getNumber())
                .isEqualTo(5);
    }

    @Test
    @DisplayName(
            "GIVEN the hooks contract WHEN read SHOULD be shiguchi.cursus.v1.PushHooks with unary methods, three decisions, and messages with the documented field numbers")
    void given_hooksContract_when_read_should_offerUnaryNotificationsAndDecisions() {
        ServiceDescriptor hooks = PushHooksProto.getDescriptor().findServiceByName("PushHooks");
        assertThat(hooks.getFullName()).isEqualTo("shiguchi.cursus.v1.PushHooks");
        assertThat(hooks.getMethods())
                .extracting(MethodDescriptor::getName)
                .containsExactly(
                        "OnTopicActive", "OnTopicIdle", "OnConnect", "OnDisconnect", "OnSubscribe", "OnClientMessage");
        for (MethodDescriptor m : hooks.getMethods()) {
            assertThat(m.isClientStreaming()).isFalse();
            assertThat(m.isServerStreaming()).isFalse();
        }
        for (String notification : new String[] {"OnTopicActive", "OnTopicIdle", "OnDisconnect"}) {
            assertThat(hooks.findMethodByName(notification).getOutputType().getFullName())
                    .isEqualTo("google.protobuf.Empty");
        }
        assertThat(hooks.findMethodByName("OnConnect").getOutputType()).isEqualTo(ConnectDecision.getDescriptor());
        assertThat(hooks.findMethodByName("OnSubscribe").getOutputType()).isEqualTo(SubscribeDecision.getDescriptor());
        assertThat(hooks.findMethodByName("OnClientMessage").getOutputType())
                .isEqualTo(ClientMessageResult.getDescriptor());
        assertThat(hooks.findMethodByName("OnTopicActive").getInputType()).isEqualTo(TopicActivity.getDescriptor());
        assertThat(hooks.findMethodByName("OnConnect").getInputType()).isEqualTo(ConnectionInfo.getDescriptor());
        assertThat(TopicActivity.getDescriptor().findFieldByName("topic").getNumber())
                .isEqualTo(1);
        assertThat(TopicActivity.getDescriptor().findFieldByName("instance").getNumber())
                .isEqualTo(2);
        assertThat(TopicActivity.getDescriptor().findFieldByName("subscribers").getNumber())
                .isEqualTo(3);
        assertThat(ConnectionInfo.getDescriptor().findFieldByName("topics").isRepeated())
                .isTrue();
        assertThat(ConnectionInfo.getDescriptor().findFieldByName("reason").getNumber())
                .isEqualTo(7);
        assertThat(field(ConnectDecision.getDescriptor(), "reject")).isEqualTo(1);
        assertThat(field(ConnectDecision.getDescriptor(), "reason")).isEqualTo(2);
        assertThat(field(ConnectDecision.getDescriptor(), "subscribe")).isEqualTo(3);
        assertThat(field(SubscribeHook.getDescriptor(), "connection")).isEqualTo(1);
        assertThat(field(SubscribeHook.getDescriptor(), "topics")).isEqualTo(2);
        assertThat(field(SubscribeDecision.getDescriptor(), "reject")).isEqualTo(1);
        assertThat(field(SubscribeDecision.getDescriptor(), "reason")).isEqualTo(2);
        assertThat(field(SubscribeDecision.getDescriptor(), "topics")).isEqualTo(3);
        assertThat(field(ClientMessage.getDescriptor(), "connection")).isEqualTo(1);
        assertThat(field(ClientMessage.getDescriptor(), "topic")).isEqualTo(2);
        assertThat(field(ClientMessage.getDescriptor(), "payload")).isEqualTo(3);
        assertThat(field(ClientMessageResult.getDescriptor(), "action")).isEqualTo(1);
        assertThat(field(ClientMessageResult.getDescriptor(), "reason")).isEqualTo(2);
        assertThat(field(ClientMessageResult.getDescriptor(), "payload")).isEqualTo(3);
        assertThat(ClientMessageResult.Action.FORWARD.getNumber()).isZero();
        assertThat(ClientMessageResult.Action.DROP.getNumber()).isEqualTo(1);
        assertThat(ClientMessageResult.Action.REJECT.getNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN an old backend answering Empty to OnConnect WHEN read as a ConnectDecision SHOULD be the default allow")
    void given_emptyReply_when_readAsConnectDecision_should_allow() throws Exception {
        ConnectDecision decision = ConnectDecision.parseFrom(
                com.google.protobuf.Empty.getDefaultInstance().toByteArray());
        assertThat(decision.getReject()).isFalse();
        assertThat(decision.getSubscribeList()).isEmpty();
    }

    private static int field(com.google.protobuf.Descriptors.Descriptor message, String name) {
        return message.findFieldByName(name).getNumber();
    }

    @Test
    @DisplayName(
            "GIVEN Send and PushPeer WHEN their descriptors are read SHOULD be POST /v1/send with pinned field numbers, and the peer service SHOULD have no HTTP binding")
    void given_send_when_descriptorsRead_should_matchTheContract() {
        MethodDescriptor send = SERVICE.findMethodByName("Send");
        assertThat(send.isServerStreaming()).isFalse();
        HttpRule rule = send.getOptions().getExtension(AnnotationsProto.http);
        assertThat(rule.getPost()).isEqualTo("/v1/send");
        assertThat(rule.getBody()).isEqualTo("*");
        var req = SendRequest.getDescriptor();
        assertThat(req.findFieldByName("principal").getNumber()).isEqualTo(1);
        assertThat(req.findFieldByName("connection_id").getNumber()).isEqualTo(2);
        assertThat(req.findFieldByName("device").getNumber()).isEqualTo(3);
        assertThat(req.findFieldByName("json").getNumber()).isEqualTo(4);
        assertThat(req.findFieldByName("message").getNumber()).isEqualTo(5);
        assertThat(req.findFieldByName("attributes").getNumber()).isEqualTo(6);
        assertThat(req.findFieldByName("require_ack").getNumber()).isEqualTo(7);
        assertThat(req.findFieldByName("timeout_ms").getNumber()).isEqualTo(8);
        assertThat(DeviceTarget.getDescriptor().findFieldByName("device_id").getNumber())
                .isEqualTo(2);
        assertThat(DeliveryStatus.getDescriptor().findFieldByName("status").getNumber())
                .isEqualTo(3);
        assertThat(DeliveryStatus.Status.DELIVERED.getNumber()).isEqualTo(1);
        assertThat(DeliveryStatus.Status.NOT_CONNECTED.getNumber()).isEqualTo(2);
        assertThat(DeliveryStatus.Status.TIMEOUT.getNumber()).isEqualTo(3);
        assertThat(DeliveryStatus.Status.REJECTED.getNumber()).isEqualTo(4);
        assertThat(SendResponse.getDescriptor().findFieldByName("deliveries").getNumber())
                .isEqualTo(2);

        ServiceDescriptor peer = PushPeerProto.getDescriptor().findServiceByName("PushPeer");
        for (MethodDescriptor m : peer.getMethods()) {
            assertThat(m.getOptions().hasExtension(AnnotationsProto.http)).isFalse();
        }
        assertThat(DeliverRequest.getDescriptor().findFieldByName("event").getNumber())
                .isEqualTo(2);
        assertThat(DeliverRequest.getDescriptor().findFieldByName("timeout_ms").getNumber())
                .isEqualTo(4);
        assertThat(DisconnectRequest.getDescriptor().findFieldByName("reason").getNumber())
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "GIVEN the origin stamp WHEN the descriptors are read SHOULD carry origin at 7 on SubscribeRequest, 6 on PublishRequest and 9 on SendRequest, and SUPPRESSED at 5 in DeliveryStatus")
    void given_originStamp_when_descriptorsRead_should_pinFieldNumbers() {
        assertThat(SubscribeRequest.getDescriptor().findFieldByName("origin").getNumber())
                .isEqualTo(7);
        assertThat(PublishRequest.getDescriptor().findFieldByName("origin").getNumber())
                .isEqualTo(6);
        assertThat(SendRequest.getDescriptor().findFieldByName("origin").getNumber())
                .isEqualTo(9);
        assertThat(DeliveryStatus.Status.SUPPRESSED.getNumber()).isEqualTo(5);
        assertThat(DeliveryStatus.Status.REJECTED.getNumber()).isEqualTo(4);
    }
}
