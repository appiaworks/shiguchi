package dev.appiaworks.shiguchi.graphql.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushSubscriptions;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Flux;

/**
 * The fetcher behind one namespace's subscription field: {@code topic} names the topic within the
 * namespace, {@code after} the id to resume from. Returns a {@code Flux} of envelopes, which
 * graphql-java delivers one {@code next} frame each; the caller is the security context's
 * {@code Authentication}, as for every other field. The optional {@code origin} is the
 * subscriber's own origin id: events published with the same origin are not delivered to it.
 */
final class PushGraphQlDataFetcher implements DataFetcher<Object> {

    static final String TRANSPORT = "graphql";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PushGraphQlContributor.Field field;
    private final PushSubscriptions subscriptions;
    private final SerdeSelector serde;

    PushGraphQlDataFetcher(PushGraphQlContributor.Field field, PushSubscriptions subscriptions, SerdeSelector serde) {
        this.field = field;
        this.subscriptions = subscriptions;
        this.serde = serde;
    }

    @Override
    public Object get(DataFetchingEnvironment env) {
        String topic = env.getArgument("topic");
        String after = env.getArgument("after");
        String origin = env.getArgument("origin");
        String full = field.namespace() + ":" + (topic == null ? "" : topic.trim());
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.<Authentication>empty())
                .flatMapMany(auth ->
                        subscriptions.open(TRANSPORT, auth.orElse(null), List.of(full), null, after, null, origin))
                .handle((event, sink) -> {
                    try {
                        sink.next(envelope(event));
                    } catch (Exception e) {
                        sink.error(new IllegalStateException(
                                "push event " + event.id() + " on " + event.topic() + " cannot be rendered: "
                                        + e.getMessage(),
                                e));
                    }
                });
    }

    /** Overload for tests and other callers that already hold the caller. */
    Flux<Map<String, Object>> stream(Authentication auth, String topic, String after) {
        return subscriptions
                .open(TRANSPORT, auth, List.of(field.namespace() + ":" + topic), null, after)
                .handle((event, sink) -> {
                    try {
                        sink.next(envelope(event));
                    } catch (Exception e) {
                        sink.error(e);
                    }
                });
    }

    Map<String, Object> envelope(PushEvent e) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("topic", e.topic().toString());
        out.put("time", e.time().toString());
        out.put("key", e.key());
        List<Map<String, Object>> attributes = new ArrayList<>(e.attributes().size());
        for (Map.Entry<String, String> a : e.attributes().entrySet()) {
            attributes.add(Map.of("key", a.getKey(), "value", a.getValue()));
        }
        out.put("attributes", attributes);
        out.put("payload", payload(e));
        return out;
    }

    private Object payload(PushEvent e) throws Exception {
        if (field.payload() == null) {
            return JSON.readValue(e.data(), Object.class);
        }
        DynamicMessage message;
        boolean asSent = e.typed() && e.typeUrl().endsWith("/" + field.payload().getFullName());
        if (asSent) {
            message = DynamicMessage.parseFrom(field.payload(), e.proto());
        } else {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(field.payload());
            JsonFormat.parser().ignoringUnknownFields().merge(new String(e.data(), StandardCharsets.UTF_8), builder);
            message = builder.build();
        }
        return serde.serializeToMapProjected(message, field.payload(), field.context(), null);
    }
}
