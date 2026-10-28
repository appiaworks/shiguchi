package dev.appiaworks.shiguchi.cursus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code POST <path>/publish} with {@code {"topic": "orders:123", "data": <json>, "attributes":
 * {...}, "key": "..."}}, or {@code "topics": [...]} for the same event on several topics; answers
 * {@code {"topic", "id"}} or {@code {"results": [...]}}. {@code POST <path>/topics/{topic}} with
 * the payload as the body is the client form, open only where the namespace sets
 * {@code client-publish: true}, and the caller must pass the subscribe check.
 */
public final class PushPublishHandler {

    private final PushProperties properties;
    private final PushPublisher publisher;
    private final TopicAuthorizer authorizer;

    public PushPublishHandler(PushProperties properties, PushPublisher publisher, TopicAuthorizer authorizer) {
        this.properties = properties;
        this.publisher = publisher;
        this.authorizer = authorizer;
    }

    public Mono<ServerResponse> publish(ServerRequest request) {
        return request.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> {
                    Request req = parse(body);
                    return PushSubscribeHandler.principal(request)
                            .flatMap(auth -> Flux.fromIterable(req.topics)
                                    .concatMap(t -> authorizer.authorizePublish(
                                            auth.orElse(null), t, properties.namespaceOf(t)))
                                    .thenMany(Flux.fromIterable(req.topics)
                                            .concatMap(t -> publisher.publishAsOperator(
                                                    t,
                                                    req.data,
                                                    req.attributes,
                                                    req.key,
                                                    null,
                                                    null,
                                                    req.origin,
                                                    fromName(auth))))
                                    .collectList()
                                    .flatMap(events -> respond(events, req.single)));
                })
                .onErrorResume(PushException.class, PushResponses::error);
    }

    public Mono<ServerResponse> clientPublish(ServerRequest request) {
        return request.bodyToMono(byte[].class)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> {
                    Topic topic = topic(request.pathVariable("topic"));
                    PushProperties.Namespace ns = properties.namespaceOf(topic);
                    if (!ns.isClientPublish()) {
                        throw PushException.forbidden(
                                "client publishing is off for namespace '" + topic.namespace() + "'");
                    }
                    String origin =
                            PushOrigin.parse(request.queryParam("origin").orElse(null));
                    return PushSubscribeHandler.principal(request)
                            .flatMap(auth -> authorizer
                                    .authorizeSubscribe(auth.orElse(null), topic, ns)
                                    .then(Mono.defer(() -> publisher.publishFromClient(
                                            topic, body, Map.of(), null, origin, fromName(auth))))
                                    .flatMap(e -> respond(List.of(e), true)));
                })
                .onErrorResume(PushException.class, PushResponses::error);
    }

    private static String fromName(Optional<Authentication> auth) {
        return auth.filter(TopicAuthorizer::isAuthenticated)
                .map(Authentication::getName)
                .orElse(null);
    }

    private record Request(
            List<Topic> topics,
            byte[] data,
            Map<String, String> attributes,
            String key,
            boolean single,
            String origin) {}

    private Request parse(byte[] body) {
        JsonNode root;
        try {
            root = body.length == 0 ? null : PushResponses.JSON.readTree(body);
        } catch (java.io.IOException e) {
            throw PushException.badRequest("body is not JSON: " + e.getMessage());
        }
        if (!(root instanceof ObjectNode obj)) throw PushException.badRequest("body must be a JSON object");
        List<Topic> topics = new ArrayList<>();
        boolean single;
        if (obj.hasNonNull("topics")) {
            if (!(obj.get("topics") instanceof ArrayNode arr) || arr.isEmpty()) {
                throw PushException.badRequest("topics must be a non-empty array");
            }
            for (JsonNode n : arr) {
                Topic t = topic(n.asText());
                if (!topics.contains(t)) topics.add(t);
            }
            single = false;
        } else if (obj.hasNonNull("topic")) {
            topics.add(topic(obj.get("topic").asText()));
            single = true;
        } else {
            throw PushException.badRequest("topic (or topics) is required");
        }
        JsonNode data = obj.get("data");
        if (data == null || data.isMissingNode()) throw PushException.badRequest("data is required");
        byte[] bytes;
        try {
            bytes = PushResponses.JSON.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            throw PushException.badRequest("data is not serialisable: " + e.getOriginalMessage());
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        JsonNode attrs = obj.get("attributes");
        if (attrs != null && !attrs.isNull()) {
            if (!attrs.isObject()) throw PushException.badRequest("attributes must be an object of strings");
            for (Map.Entry<String, JsonNode> f : attrs.properties()) {
                if (!f.getValue().isValueNode())
                    throw PushException.badRequest("attribute '" + f.getKey() + "' must be a string");
                attributes.put(f.getKey(), f.getValue().asText());
            }
        }
        String key = obj.hasNonNull("key") ? obj.get("key").asText() : null;
        String origin =
                obj.hasNonNull("origin") ? PushOrigin.parse(obj.get("origin").asText()) : null;
        for (String name : attributes.keySet()) {
            if (name.startsWith(PushOrigin.RESERVED_PREFIX)) {
                throw PushException.badRequest("attribute '" + name + "' is reserved: the '"
                        + PushOrigin.RESERVED_PREFIX + "' prefix belongs to the gateway");
            }
        }
        return new Request(topics, bytes, attributes, key, single, origin);
    }

    private Topic topic(String text) {
        Topic topic;
        try {
            topic = Topic.parse(text);
        } catch (IllegalArgumentException e) {
            throw PushException.badRequest(e.getMessage());
        }
        if (properties.namespaceOf(topic) == null) throw PushException.unknownNamespace(topic);
        return topic;
    }

    private static Mono<ServerResponse> respond(List<PushEvent> events, boolean single) {
        JsonNode body;
        if (single) {
            body = PushResponses.published(events.get(0));
        } else {
            ObjectNode node = PushResponses.JSON.createObjectNode();
            ArrayNode results = node.putArray("results");
            events.forEach(e -> results.add(PushResponses.published(e)));
            body = node;
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(bytes);
    }
}
