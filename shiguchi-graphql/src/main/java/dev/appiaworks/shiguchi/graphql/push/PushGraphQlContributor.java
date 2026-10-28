package dev.appiaworks.shiguchi.graphql.push;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import dev.appiaworks.shiguchi.cursus.PushSubscriptions;
import dev.appiaworks.shiguchi.graphql.GraphQlContributor;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One subscription field per push namespace: {@code Subscription.<ns>Events(topic: String!,
 * after: ID, origin: String): <Ns>EventsEnvelope}, the envelope carrying the event's id (what a client resumes
 * from), topic, time, key, attributes and {@code payload}: the namespace's {@code payload-type}
 * rendered as the generated GraphQL type of that message, with field selection, or {@code JSON}
 * for an untyped namespace. The fetcher subscribes through {@link PushSubscriptions}, so the
 * engine, the authorization, the resume, the client queue, the admin views and the metrics are
 * the same as over SSE, WebSocket or gRPC; a subscription is served over graphql-transport-ws
 * where {@code spring.graphql.websocket.path} is set.
 *
 * <p>Present only when the push module is on the classpath and {@code shiguchi.cursus.enabled}
 * is set: the GraphQL module never depends on the push module at runtime.
 */
@Component
@ConditionalOnClass(name = "dev.appiaworks.shiguchi.cursus.PushSubscriptions")
@ConditionalOnProperty(prefix = "shiguchi.cursus", name = "enabled", havingValue = "true")
public class PushGraphQlContributor implements GraphQlContributor {

    private static final Logger log = LoggerFactory.getLogger(PushGraphQlContributor.class);
    static final String ATTRIBUTE_TYPE = "PushAttribute";

    private final PushProperties push;
    private final PushSubscriptions subscriptions;
    private final GrpcRouteRegistry registry;
    private final SerdeSelector serde;

    public PushGraphQlContributor(
            PushProperties push, PushSubscriptions subscriptions, GrpcRouteRegistry registry, SerdeSelector serde) {
        this(push, subscriptions, registry, serde, null);
    }

    /**
     * The constructor Spring uses: {@code upstreams} is taken only so the upstream-backed topics
     * have declared their namespaces (and payload types) before the fields are read.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PushGraphQlContributor(
            PushProperties push,
            PushSubscriptions subscriptions,
            GrpcRouteRegistry registry,
            SerdeSelector serde,
            dev.appiaworks.shiguchi.cursus.PushUpstreams upstreams) {
        this.push = push;
        this.subscriptions = subscriptions;
        this.registry = registry;
        this.serde = serde;
    }

    /** One namespace's field; {@code payload} and {@code context} are null for an untyped namespace. */
    record Field(
            String namespace,
            PushProperties.Namespace settings,
            String name,
            String envelope,
            Descriptor payload,
            ServiceEndpointContext context) {}

    @Override
    public void contributeSchema(Schema schema) {
        List<Field> fields = fields();
        if (fields.isEmpty()) return;
        schema.type(ATTRIBUTE_TYPE, "type " + ATTRIBUTE_TYPE + " {\n  key: String!\n  value: String!\n}\n");
        for (Field f : fields) {
            String payloadType = f.payload() == null ? "JSON" : schema.objectType(f.payload());
            schema.type(
                    f.envelope(),
                    "type " + f.envelope() + " {\n"
                            + "  id: ID!\n"
                            + "  topic: String!\n"
                            + "  time: DateTime!\n"
                            + "  key: String\n"
                            + "  attributes: [" + ATTRIBUTE_TYPE + "!]!\n"
                            + "  payload: " + payloadType + "\n"
                            + "}\n");
            schema.subscriptionField(f.name() + "(topic: String!, after: ID, origin: String): " + f.envelope());
        }
        log.info(
                "Push: {} GraphQL subscription field(s): {}",
                fields.size(),
                fields.stream().map(Field::name).toList());
    }

    @Override
    public void contributeDataFetchers(GraphQLCodeRegistry.Builder registry) {
        for (Field f : fields()) {
            registry.dataFetcher(
                    FieldCoordinates.coordinates("Subscription", f.name()),
                    new PushGraphQlDataFetcher(f, subscriptions, serde));
        }
    }

    /** The fields, resolved against the current descriptors (they may have been reloaded since the last build). */
    List<Field> fields() {
        List<Field> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map.Entry<String, PushProperties.Namespace> e :
                push.getNamespaces().entrySet()) {
            String namespace = e.getKey();
            PushProperties.Namespace ns = e.getValue();
            if (!ns.isGraphql()) continue;
            String name = ns.graphqlFieldOr(namespace);
            if (!names.add(name)) {
                throw new IllegalStateException("shiguchi.cursus: two namespaces share the GraphQL subscription field '"
                        + name + "'; set graphql-field on one of them");
            }
            String envelope = Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Envelope";
            Descriptor payload = null;
            ServiceEndpointContext context = null;
            String payloadType = ns.getPayloadType();
            if (payloadType != null && !payloadType.isBlank()) {
                Found found = find(payloadType.trim());
                if (found == null) {
                    throw new IllegalStateException("shiguchi.cursus.namespaces." + namespace + ".payload-type '"
                            + payloadType + "' is not a message of any service the gateway serves");
                }
                payload = found.descriptor();
                context = found.context();
            }
            out.add(new Field(namespace, ns, name, envelope, payload, context));
        }
        return out;
    }

    record Found(Descriptor descriptor, ServiceEndpointContext context) {}

    /** The message with that full name among the served services' descriptors, nested types included. */
    Found find(String fullName) {
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            for (FileDescriptor fd : ctx.getFileDescriptors().values()) {
                Descriptor d = find(fd.getMessageTypes(), fullName);
                if (d != null) return new Found(d, ctx);
            }
        }
        return null;
    }

    private static Descriptor find(List<Descriptor> types, String fullName) {
        for (Descriptor d : types) {
            if (d.getFullName().equals(fullName)) return d;
            Descriptor nested = find(d.getNestedTypes(), fullName);
            if (nested != null) return nested;
        }
        return null;
    }
}
