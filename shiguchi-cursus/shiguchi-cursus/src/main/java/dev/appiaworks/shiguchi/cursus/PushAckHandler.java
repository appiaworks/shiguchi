package dev.appiaworks.shiguchi.cursus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * {@code POST <path>/ack} with {@code {"topic": "inbox:alice", "id": "…", "session"?: "…"}}, or
 * {@code {"topic", "id", "group", "member"}} for a consumer-group member:
 * the acknowledgement of SSE clients (which have no upstream) and of anyone else over HTTP. The
 * caller's session is the principal's, or the {@code session} it names on a {@code CLIENT}
 * namespace. Answers {@code {"topic", "cursor"}}.
 */
public final class PushAckHandler {

    private final PushSessions sessions;
    private final TopicAuthorizer authorizer;

    public PushAckHandler(PushSessions sessions, TopicAuthorizer authorizer) {
        this.sessions = sessions;
        this.authorizer = authorizer;
    }

    public Mono<ServerResponse> ack(ServerRequest request) {
        return request.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> {
                    JsonNode json;
                    try {
                        json = body.length == 0 ? null : PushResponses.JSON.readTree(body);
                    } catch (java.io.IOException e) {
                        return Mono.error(PushException.badRequest("body is not JSON: " + e.getMessage()));
                    }
                    if (!(json instanceof ObjectNode obj) || !obj.hasNonNull("topic") || !obj.hasNonNull("id")) {
                        return Mono.error(PushException.badRequest("a JSON object with topic and id is required"));
                    }
                    String topic = obj.get("topic").asText();
                    String id = obj.get("id").asText();
                    String session =
                            obj.hasNonNull("session") ? obj.get("session").asText() : null;
                    String group = obj.hasNonNull("group") ? obj.get("group").asText() : null;
                    String member = obj.hasNonNull("member") ? obj.get("member").asText() : null;
                    return PushSubscribeHandler.principal(request)
                            .flatMap(auth -> group != null && !group.isBlank()
                                    ? ack(sessions.groups(), auth.orElse(null), group, member, topic, id)
                                    : sessions.ack(auth.orElse(null), session, topic, id, authorizer))
                            .then(ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(("{\"topic\":" + quote(topic) + ",\"cursor\":" + quote(id) + "}")
                                            .getBytes(StandardCharsets.UTF_8)));
                })
                .onErrorResume(PushException.class, PushResponses::error);
    }

    private Mono<Void> ack(
            PushGroups groups,
            org.springframework.security.core.Authentication auth,
            String group,
            String member,
            String topic,
            String id) {
        if (groups == null) return Mono.error(PushException.badRequest("consumer groups are not configured"));
        return groups.ack(auth, group, member, topic, id, authorizer);
    }

    private static String quote(String s) {
        try {
            return PushResponses.JSON.writeValueAsString(s);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
