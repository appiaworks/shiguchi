package dev.appiaworks.shiguchi.cursus;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSON bodies of the push endpoints' non-stream answers. */
final class PushResponses {

    static final ObjectMapper JSON = JsonMapper.builder(dev.appiaworks.protobuf.extras.serde.JsonFactories.create())
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private PushResponses() {}

    static Mono<ServerResponse> error(PushException e) {
        ObjectNode body = JSON.createObjectNode();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        // Serialised here, not by the codec: on the Boot 4 line the Jackson 3 codec sees a Jackson 2
        // tree as a plain bean and writes its getters.
        return ServerResponse.status(e.status())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static ObjectNode published(PushEvent e) {
        ObjectNode node = JSON.createObjectNode();
        node.put("topic", e.topic().toString());
        node.put("id", e.id());
        return node;
    }
}
