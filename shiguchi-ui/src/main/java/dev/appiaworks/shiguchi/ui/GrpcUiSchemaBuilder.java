package dev.appiaworks.shiguchi.ui;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Builds a JSON-serialisable schema tree from all registered gRPC services for the embedded UI.
 *
 * Each service's methods are described with their full input message field tree (inlined
 * recursively up to MAX_DEPTH, with circular-ref protection via a visited set).
 *
 * Rebuilt on demand by GatewayRefreshScheduler when downstream schemas change.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class GrpcUiSchemaBuilder implements dev.appiaworks.shiguchi.routing.SchemaReloadListener {

    private static final Logger log = LoggerFactory.getLogger(GrpcUiSchemaBuilder.class);
    private static final int MAX_DEPTH = 8;

    private static final Map<String, String> WELL_KNOWN = Map.ofEntries(
            Map.entry("google.protobuf.Timestamp", "TIMESTAMP"),
            Map.entry("google.protobuf.Duration", "DURATION"),
            Map.entry("google.protobuf.FieldMask", "FIELD_MASK"),
            Map.entry("google.protobuf.Struct", "STRUCT"),
            Map.entry("google.protobuf.Value", "VALUE"),
            Map.entry("google.protobuf.ListValue", "LIST_VALUE"),
            Map.entry("google.protobuf.Any", "ANY"),
            Map.entry("google.protobuf.Empty", "EMPTY"),
            Map.entry("google.protobuf.StringValue", "STRING_VALUE"),
            Map.entry("google.protobuf.Int32Value", "INT32_VALUE"),
            Map.entry("google.protobuf.Int64Value", "INT64_VALUE"),
            Map.entry("google.protobuf.UInt32Value", "UINT32_VALUE"),
            Map.entry("google.protobuf.UInt64Value", "UINT64_VALUE"),
            Map.entry("google.protobuf.FloatValue", "FLOAT_VALUE"),
            Map.entry("google.protobuf.DoubleValue", "DOUBLE_VALUE"),
            Map.entry("google.protobuf.BoolValue", "BOOL_VALUE"),
            Map.entry("google.protobuf.BytesValue", "BYTES_VALUE"));

    /**
     * google.type messages the gateway writes as text (shiguchi.json.scalar-types), edited as
     * one value while mapped. Money is absent: its fields still bind, as the object form is accepted.
     */
    private static final Map<String, String> GOOGLE_TYPES = Map.of(
            ProtoJsonMapping.DATE, "DATE",
            ProtoJsonMapping.TIME_OF_DAY, "TIME_OF_DAY",
            ProtoJsonMapping.DATE_TIME, "DATE_TIME",
            ProtoJsonMapping.DECIMAL, "DECIMAL");

    private final GrpcRouteRegistry registry;
    private volatile List<GrpcUiSchemaDto.ServiceSchema> schema = Collections.emptyList();

    public GrpcUiSchemaBuilder(GrpcRouteRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void build() {
        this.schema = buildSchema();
        log.info(
                "Built gRPC UI schema: {} services",
                schema.stream().mapToLong(s -> 1).count());
    }

    public void rebuild() {
        this.schema = buildSchema();
        log.info(
                "Rebuilt gRPC UI schema: {} services",
                schema.stream().mapToLong(s -> 1).count());
    }

    @Override
    public void onSchemaReloaded() {
        rebuild();
    }

    public List<GrpcUiSchemaDto.ServiceSchema> getSchema() {
        return schema;
    }

    // -------------------------------------------------------------------------

    private List<GrpcUiSchemaDto.ServiceSchema> buildSchema() {
        List<GrpcUiSchemaDto.ServiceSchema> services = new ArrayList<>();
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            // Synthetic REST_DOWNSTREAM services cannot be invoked over gRPC; the console lists gRPC only.
            if (ctx.isRestDownstream()) continue;
            String configName = ctx.getConfig() != null ? ctx.getConfig().getName() : "";
            for (ServiceDescriptor sd : ctx.getServiceByFqn().values()) {
                List<GrpcUiSchemaDto.MethodSchema> methods = new ArrayList<>();
                for (MethodDescriptor md : sd.getMethods()) {
                    methods.add(buildMethodSchema(md, ctx));
                }
                String namespace = sd.getFile() != null ? sd.getFile().getPackage() : "";
                services.add(new GrpcUiSchemaDto.ServiceSchema(
                        sd.getFullName(), ctx.target(), Collections.unmodifiableList(methods), namespace, configName));
            }
        }
        return Collections.unmodifiableList(services);
    }

    private GrpcUiSchemaDto.MethodSchema buildMethodSchema(MethodDescriptor md, ServiceEndpointContext ctx) {
        String serviceFqn = md.getService().getFullName();
        String methodName = md.getName();
        String httpMethod = null;
        String httpPath = null;

        for (RouteDefinition route : ctx.getRoutes()) {
            if (route.getFullServiceName().equals(serviceFqn)
                    && route.getMethodName().equals(methodName)
                    && route.hasRestBinding()) {
                httpMethod = route.getHttpMethod();
                httpPath = route.getHttpPath();
                break;
            }
        }

        GrpcUiSchemaDto.MessageSchema inputSchema = buildMessageSchema(md.getInputType(), new HashSet<>(), 0);

        return new GrpcUiSchemaDto.MethodSchema(
                methodName,
                serviceFqn,
                resolveMethodType(md),
                inputSchema,
                md.getOutputType().getFullName(),
                httpMethod,
                httpPath);
    }

    private GrpcUiSchemaDto.MessageSchema buildMessageSchema(Descriptor descriptor, Set<String> visited, int depth) {
        if (depth > MAX_DEPTH || visited.contains(descriptor.getFullName())) {
            return new GrpcUiSchemaDto.MessageSchema(descriptor.getFullName(), Collections.emptyList());
        }
        Set<String> childVisited = new HashSet<>(visited);
        childVisited.add(descriptor.getFullName());

        List<GrpcUiSchemaDto.FieldSchema> fields = new ArrayList<>();
        for (FieldDescriptor fd : descriptor.getFields()) {
            fields.add(buildFieldSchema(fd, childVisited, depth + 1));
        }
        return new GrpcUiSchemaDto.MessageSchema(descriptor.getFullName(), Collections.unmodifiableList(fields));
    }

    private GrpcUiSchemaDto.FieldSchema buildFieldSchema(FieldDescriptor fd, Set<String> visited, int depth) {
        String type = fd.getType().name().toLowerCase();

        String label;
        if (fd.isMapField()) {
            label = "map";
        } else if (fd.isRepeated()) {
            label = "repeated";
        } else {
            label = "optional";
        }

        String oneofName =
                (fd.getContainingOneof() != null) ? fd.getContainingOneof().getName() : null;

        String typeName = null;
        List<GrpcUiSchemaDto.FieldSchema> nestedFields = Collections.emptyList();
        List<GrpcUiSchemaDto.EnumValueSchema> enumValues = Collections.emptyList();
        String wellKnownType = null;
        boolean mapEntry = false;

        switch (fd.getType()) {
            case MESSAGE -> {
                Descriptor msgType = fd.getMessageType();
                typeName = msgType.getFullName();
                wellKnownType = WELL_KNOWN.get(typeName);
                if (wellKnownType == null && ProtoJsonMapping.isMapped(msgType)) {
                    wellKnownType = GOOGLE_TYPES.get(typeName);
                }
                if (fd.isMapField()) {
                    mapEntry = true;
                    List<GrpcUiSchemaDto.FieldSchema> entryFields = new ArrayList<>();
                    for (FieldDescriptor entryFd : msgType.getFields()) {
                        entryFields.add(buildFieldSchema(entryFd, visited, depth + 1));
                    }
                    nestedFields = Collections.unmodifiableList(entryFields);
                } else if (wellKnownType == null) {
                    nestedFields = buildMessageSchema(msgType, visited, depth).fields();
                }
            }
            case ENUM -> {
                typeName = fd.getEnumType().getFullName();
                enumValues = fd.getEnumType().getValues().stream()
                        .map(v -> new GrpcUiSchemaDto.EnumValueSchema(v.getName(), v.getNumber()))
                        .toList();
            }
            default -> {
                /* primitives: no additional info */
            }
        }

        return new GrpcUiSchemaDto.FieldSchema(
                fd.getName(),
                fd.getJsonName(),
                type,
                label,
                typeName,
                nestedFields,
                enumValues,
                oneofName,
                mapEntry,
                wellKnownType);
    }

    private static String resolveMethodType(MethodDescriptor md) {
        if (!md.isClientStreaming() && !md.isServerStreaming()) return "UNARY";
        if (!md.isClientStreaming()) return "SERVER_STREAMING";
        if (!md.isServerStreaming()) return "CLIENT_STREAMING";
        return "BIDI_STREAMING";
    }
}
