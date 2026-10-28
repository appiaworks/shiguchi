package dev.appiaworks.shiguchi.openapi;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.HttpPathTemplate;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Builds a complete OpenAPI 3 spec from all registered gRPC routes at startup.
 *
 * Each REST-bound RouteDefinition becomes a PathItem + Operation with:
 *  - Path parameters from {var} template segments
 *  - Query parameters for GET/DELETE from non-path input fields
 *  - Request body for POST/PUT/PATCH
 *  - Response schema from the output Descriptor
 *  - Component schemas deduplicated across all routes
 */
@Component
@DependsOn({"dynamicRouteLoader", "staticRouteLoader", "restDownstreamRouteLoader"})
public class GatewayOpenApiBuilder {

    private static final Logger log = LoggerFactory.getLogger(GatewayOpenApiBuilder.class);

    /**
     * Proto field names that activate gateway conventions. They are exposed as the
     * {@code ?pageSize} / {@code ?sort=} / {@code ?fields=} query parameters rather than
     * under their raw proto names.
     */
    private static final Set<String> CONVENTION_FIELDS = Set.of("page_request", "sort_fields", "read_mask");

    private final GrpcRouteRegistry registry;
    private final GatewayProperties properties;
    private final ProtoToOpenApiSchemaConverter converter;
    private volatile OpenAPI builtSpec;

    /** Non-JSON media types callers may use on gRPC-backed routes (Avro, ...); advertised next to JSON. */
    private final List<dev.appiaworks.shiguchi.rest.RestBodyCodec> codecs;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayOpenApiBuilder(
            GrpcRouteRegistry registry,
            GatewayProperties properties,
            ProtoToOpenApiSchemaConverter converter,
            org.springframework.beans.factory.ObjectProvider<dev.appiaworks.shiguchi.rest.RestBodyCodec> codecs) {
        this(registry, properties, converter, codecs.orderedStream().toList());
    }

    public GatewayOpenApiBuilder(
            GrpcRouteRegistry registry,
            GatewayProperties properties,
            ProtoToOpenApiSchemaConverter converter,
            List<dev.appiaworks.shiguchi.rest.RestBodyCodec> codecs) {
        this.registry = registry;
        this.properties = properties;
        this.converter = converter;
        this.codecs = codecs != null ? List.copyOf(codecs) : List.of();
    }

    /** JSON-only builder, for callers that wire by hand. */
    public GatewayOpenApiBuilder(
            GrpcRouteRegistry registry, GatewayProperties properties, ProtoToOpenApiSchemaConverter converter) {
        this(registry, properties, converter, List.of());
    }

    /** {@code application/json} plus one entry per codec, each carrying the codec's schema extensions. */
    private Content content(Schema<?> schema, com.google.protobuf.Descriptors.Descriptor type) {
        Content content = new Content().addMediaType("application/json", new MediaType().schema((Schema) schema));
        for (dev.appiaworks.shiguchi.rest.RestBodyCodec codec : codecs) {
            MediaType mt = new MediaType().schema((Schema) schema);
            Map<String, Object> extensions = codec.openApiSchemaExtensions(type);
            if (!extensions.isEmpty()) mt.setExtensions(new java.util.LinkedHashMap<>(extensions));
            content.addMediaType(codec.mediaType(), mt);
        }
        return content;
    }

    @PostConstruct
    public void build() {
        this.builtSpec = buildSpec();
        log.info(
                "Built OpenAPI spec with {} paths",
                builtSpec.getPaths() != null ? builtSpec.getPaths().size() : 0);
    }

    public void rebuild() {
        this.builtSpec = buildSpec();
        log.info(
                "Rebuilt OpenAPI spec with {} paths",
                builtSpec.getPaths() != null ? builtSpec.getPaths().size() : 0);
    }

    public OpenAPI getSpec() {
        return builtSpec;
    }

    // -------------------------------------------------------------------------

    private OpenAPI buildSpec() {
        Map<String, Schema<?>> componentsSchemas = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Paths paths = new Paths();
        Components components = new Components();

        for (ServiceEndpointContext ctx : registry.allContexts()) {
            if (!ctx.getConfig().isOpenapiEnabled(properties.getOpenapi().isEnabled())) continue;

            // A REST_DOWNSTREAM context has no descriptors; its own document is merged instead.
            if (ctx.isRestDownstream()) {
                DownstreamSpecMerger.merge(ctx, paths, components);
                continue;
            }

            for (RouteDefinition route : ctx.getRoutes()) {
                if (!route.hasRestBinding()) continue;

                Optional<MethodDescriptor> mdOpt = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName());
                if (mdOpt.isEmpty()) continue;

                MethodDescriptor md = mdOpt.get();
                Descriptor inputDesc = md.getInputType();
                Descriptor outputDesc = md.getOutputType();

                Operation op =
                        buildOperation(route, inputDesc, outputDesc, ctx.getPrintOptions(), componentsSchemas, visited);

                PathItem pathItem = paths.computeIfAbsent(openApiPath(route.getHttpPath()), p -> new PathItem());
                setOperation(pathItem, route.getHttpMethod(), op);
            }
        }

        if (!componentsSchemas.isEmpty()) {
            if (components.getSchemas() == null) components.setSchemas(new LinkedHashMap<>());
            componentsSchemas.forEach((name, schema) -> components.getSchemas().putIfAbsent(name, (Schema) schema));
        }

        return new OpenAPI()
                .info(new Info()
                        .title("Shiguchi")
                        .version("1.0")
                        .description("Auto-generated from gRPC proto descriptors"))
                .paths(paths)
                .components(components);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Operation buildOperation(
            RouteDefinition route,
            Descriptor inputDesc,
            Descriptor outputDesc,
            JsonPrintOptions print,
            Map<String, Schema<?>> componentsSchemas,
            Set<String> visited) {

        String httpMethod = route.getHttpMethod().toUpperCase();
        Set<String> pathVarNames = extractPathVarNames(route.getHttpPath());

        List<Parameter> parameters = new ArrayList<>();
        // Path parameters
        for (String varName : pathVarNames) {
            parameters.add(
                    new Parameter().name(varName).in("path").required(true).schema((Schema)
                            new Schema<>().type("string")));
        }

        Operation op = new Operation()
                .operationId(operationId(route))
                .addTagsItem(shortServiceTag(route.getFullServiceName()))
                .parameters(parameters);

        // Whatever documents this method: the (shiguchi.api.graphql) description when the proto set one,
        // otherwise the comment above the rpc (PB_FILE mode, where protoc kept the source info).
        // The first line becomes the summary, which is what a Swagger UI list shows.
        String described = route.getGraphQlDescription();
        if (described != null && !described.isBlank()) {
            op.setDescription(described);
            int firstBreak = described.indexOf('\n');
            op.setSummary(firstBreak > 0 ? described.substring(0, firstBreak).trim() : described);
        }

        // The rule's `body` decides what the request body is: a named top-level field maps the
        // body onto that field alone (the other fields stay query parameters); "*" or an unset
        // body on a POST/PUT/PATCH is the whole request message.
        String bodyName = route.getHttpBody();
        FieldDescriptor bodyField =
                bodyName != null && !"*".equals(bodyName) ? inputDesc.findFieldByName(bodyName) : null;
        boolean hasBody = bodyField != null || List.of("POST", "PUT", "PATCH").contains(httpMethod);
        // google.api.HttpBody: the body is raw bytes of any type, not the HttpBody message as JSON.
        boolean rawRequest = dev.appiaworks.shiguchi.rest.HttpBodySupport.rawRequest(inputDesc, bodyName);

        if (rawRequest) {
            op.requestBody(new RequestBody().required(true).content(binaryContent()));
        } else if (bodyField != null) {
            Schema<?> bodySchema = converter.fieldSchema(bodyField, componentsSchemas, visited, 0);
            Descriptor bodyType =
                    bodyField.getType() == FieldDescriptor.Type.MESSAGE ? bodyField.getMessageType() : null;
            op.requestBody(new RequestBody()
                    .required(true)
                    .content(bodyType != null ? content(bodySchema, bodyType) : jsonOnly(bodySchema)));
        } else if (hasBody) {
            Schema<?> inputSchema = converter.schemaForDescriptor(inputDesc, componentsSchemas, visited);
            op.requestBody(new RequestBody().required(true).content(content(inputSchema, inputDesc)));
        }
        if (!hasBody || bodyField != null) {
            // Query parameters from non-path, non-body input fields. The convention fields are
            // skipped here and advertised below under the query-param spellings the gateway
            // actually accepts (?pageSize / ?sort= / ?fields=), not their raw proto names.
            for (FieldDescriptor field : inputDesc.getFields()) {
                String jsonName = field.getJsonName();
                if (field == bodyField) continue;
                if (boundByPath(pathVarNames, jsonName, field.getName())) continue;
                if (CONVENTION_FIELDS.contains(field.getName())) continue;
                parameters.add(new Parameter()
                        .name(jsonName)
                        .in("query")
                        .required(false)
                        .schema((Schema) converter.fieldSchema(field, componentsSchemas, visited, 0)));
            }
        }

        addConventionParameters(parameters, inputDesc, outputDesc);

        // Requests accept either spelling, so they keep the default schema; the response is shaped
        // by the service's print options.
        FieldDescriptor responseBodyField = responseBodyField(route, outputDesc);
        boolean serverStreaming = route.getMethodType() == io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING;
        // google.api.HttpBody: raw bytes, not JSON. A stream sends whole messages (response_body
        // does not apply), so a streamed download is raw only when the output type itself is one.
        boolean rawResponse = serverStreaming
                ? dev.appiaworks.shiguchi.rest.HttpBodySupport.isHttpBody(outputDesc)
                : dev.appiaworks.shiguchi.rest.HttpBodySupport.rawResponse(outputDesc, route.getHttpResponseBody());
        ApiResponse ok;
        if (rawResponse) {
            ok = new ApiResponse().description("OK").content(binaryContent());
        } else if (responseBodyField != null) {
            // google.api.http's response_body: the caller gets that one field, always as JSON.
            ok = new ApiResponse()
                    .description("OK")
                    .content(jsonOnly(
                            converter.responseFieldSchema(responseBodyField, componentsSchemas, visited, print)));
        } else {
            Schema<?> responseSchema =
                    converter.responseSchemaForDescriptor(outputDesc, componentsSchemas, visited, print);
            ok = serverStreaming
                    // The schema is one message of the stream; the media type says how they are framed.
                    ? new ApiResponse()
                            .description("OK — a stream of messages, one per NDJSON line or per Server-Sent Event")
                            .content(streamingContent(responseSchema))
                    : new ApiResponse().description("OK").content(content(responseSchema, outputDesc));
        }
        op.responses(new ApiResponses()
                .addApiResponse("200", ok)
                .addApiResponse("default", new ApiResponse().description("Error")));

        return op;
    }

    /**
     * Advertises the convention-driven query parameters the gateway accepts, so they are
     * discoverable in Swagger UI rather than folklore. Each is emitted only when the request
     * (or response) message actually carries the field that activates it.
     *
     * @see <a href="../../../../../docs/projections-paging-sorting.md">projections, paging, sorting</a>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addConventionParameters(
            List<Parameter> parameters, Descriptor inputDesc, Descriptor outputDesc) {

        if (inputDesc.findFieldByName("page_request") != null) {
            parameters.add(new Parameter()
                    .name("pageSize")
                    .in("query")
                    .required(false)
                    .description("Maximum number of items to return.")
                    .schema((Schema) new Schema<>().type("integer").format("int32")));
            parameters.add(new Parameter()
                    .name("pageToken")
                    .in("query")
                    .required(false)
                    .description("Opaque cursor from a previous response's X-Next-Page-Token header.")
                    .schema((Schema) new Schema<>().type("string")));
        }

        if (inputDesc.findFieldByName("sort_fields") != null) {
            parameters.add(new Parameter()
                    .name("sort")
                    .in("query")
                    .required(false)
                    .description("Comma-separated sort keys, each optionally suffixed with "
                            + "':asc' or ':desc' (ascending when omitted). Example: author:asc,year:desc")
                    .schema((Schema) new Schema<>().type("string")));
        }

        if (inputDesc.findFieldByName("read_mask") != null) {
            Schema<?> fieldsSchema = new Schema<>().type("string");
            // Enumerate the top-level response fields so Swagger UI can suggest valid values.
            List<String> selectable = selectableFieldNames(outputDesc);
            if (!selectable.isEmpty()) {
                fieldsSchema.setExample(String.join(",", selectable.subList(0, Math.min(2, selectable.size()))));
            }
            parameters.add(new Parameter()
                    .name("fields")
                    .in("query")
                    .required(false)
                    .description("Comma-separated field projection. Accepts camelCase or snake_case, "
                            + "and dotted paths for nested fields. May also be supplied as the "
                            + "X-Fields header."
                            + (selectable.isEmpty() ? "" : " Selectable: " + String.join(", ", selectable)))
                    .schema((Schema) fieldsSchema));
        }
    }

    /**
     * Top-level field names of the repeated items message in a paged response, falling back to
     * the response's own fields — the set a {@code ?fields=} projection can name.
     */
    private static List<String> selectableFieldNames(Descriptor outputDesc) {
        Descriptor target = outputDesc;
        for (FieldDescriptor f : outputDesc.getFields()) {
            if (f.isRepeated() && f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                target = f.getMessageType();
                break;
            }
        }
        List<String> names = new ArrayList<>();
        for (FieldDescriptor f : target.getFields()) {
            if (!CONVENTION_FIELDS.contains(f.getName())) names.add(f.getJsonName());
        }
        return names;
    }

    /** A {@code google.api.HttpBody}: raw bytes whose media type the RPC decides at run time. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Content binaryContent() {
        return new Content().addMediaType("*/*", new MediaType().schema((Schema)
                        new Schema<>().type("string").format("binary")));
    }

    /** A server-streaming response: NDJSON by default, Server-Sent Events on request. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Content streamingContent(Schema<?> itemSchema) {
        return new Content()
                .addMediaType("application/x-ndjson", new MediaType().schema((Schema) itemSchema))
                .addMediaType("text/event-stream", new MediaType().schema((Schema) itemSchema));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    /**
     * The field {@code response_body} sends in place of the whole response, where the gateway
     * honours it: unary routes that are not merges. Null otherwise, and for a name the message
     * does not have (the gateway answers that route with an error, not with the field).
     */
    private static FieldDescriptor responseBodyField(RouteDefinition route, Descriptor outputDesc) {
        String name = route.getHttpResponseBody();
        if (name == null || name.isBlank()) return null;
        if (route.isMerge() || route.getMethodType() != io.grpc.MethodDescriptor.MethodType.UNARY) return null;
        return outputDesc.findFieldByName(name);
    }

    private static Content jsonOnly(Schema<?> schema) {
        return new Content().addMediaType("application/json", new MediaType().schema((Schema) schema));
    }

    /** A field is bound by the path when a variable names it or reaches into it ({@code {book.id}}). */
    private static boolean boundByPath(Set<String> pathVarNames, String jsonName, String protoName) {
        for (String var : pathVarNames) {
            if (var.equals(jsonName) || var.equals(protoName)) return true;
            if (var.startsWith(jsonName + ".") || var.startsWith(protoName + ".")) return true;
        }
        return false;
    }

    private static Set<String> extractPathVarNames(String httpPath) {
        if (!HttpPathTemplate.isTemplate(httpPath)) return new HashSet<>();
        try {
            return new LinkedHashSet<>(HttpPathTemplate.of(httpPath).variableNames());
        } catch (IllegalArgumentException e) {
            return new HashSet<>(); // the registry skipped this route; nothing to describe
        }
    }

    /**
     * The OpenAPI path for a template: {@code {name=shelves/*}} becomes {@code {name}}, since an
     * OpenAPI path parameter is a name only; the pattern is left to the backend to enforce.
     */
    private static String openApiPath(String httpPath) {
        if (!HttpPathTemplate.isTemplate(httpPath)) return httpPath;
        try {
            return HttpPathTemplate.of(httpPath).toOpenApiPath();
        } catch (IllegalArgumentException e) {
            return httpPath;
        }
    }

    private static String operationId(RouteDefinition route) {
        String methodName = route.getMethodName();
        return Character.toLowerCase(methodName.charAt(0)) + methodName.substring(1);
    }

    private static String shortServiceTag(String fullServiceName) {
        String simple = fullServiceName.contains(".")
                ? fullServiceName.substring(fullServiceName.lastIndexOf('.') + 1)
                : fullServiceName;
        return simple.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    @SuppressWarnings("rawtypes")
    private static void setOperation(PathItem pathItem, String method, Operation op) {
        switch (method.toUpperCase()) {
            case "GET" -> pathItem.setGet(op);
            case "POST" -> pathItem.setPost(op);
            case "PUT" -> pathItem.setPut(op);
            case "PATCH" -> pathItem.setPatch(op);
            case "DELETE" -> pathItem.setDelete(op);
        }
    }
}
