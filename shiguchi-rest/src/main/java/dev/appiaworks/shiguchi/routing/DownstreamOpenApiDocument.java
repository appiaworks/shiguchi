package dev.appiaworks.shiguchi.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The gateway-relevant slice of a downstream OpenAPI 3 document: one entry per operation with its
 * HTTP method, path template, {@code operationId}, parameters, request and response schemas, and
 * the gateway vendor extensions, plus the reusable component schemas.
 *
 * <p>Deliberately not a full OpenAPI model. The rest module needs enough to register proxy routes
 * and to derive synthetic proto descriptors for the GraphQL surface, and must not depend on
 * swagger-core; the openapi module re-parses the same bytes with the real model when it merges the
 * consolidated spec. JSON is read with Jackson and YAML with SnakeYAML, both already on the Boot
 * classpath, so no parser dependency is added. Schemas stay as the raw maps the document declared;
 * only {@code $ref}s to component parameters, request bodies and responses are resolved here.
 */
public final class DownstreamOpenApiDocument {

    /** HTTP verbs that may appear as keys of an OpenAPI path item. */
    private static final Set<String> VERBS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /** Vendor extension carrying the scopes the gateway enforces before proxying. */
    public static final String AUTH_SCOPES_EXTENSION = "x-gateway-auth-scopes";

    public static final String MEDIA_JSON = "application/json";
    public static final String MEDIA_YAML = "application/yaml";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One declared parameter, {@code $ref}s already resolved. */
    public record Parameter(String name, String in, boolean required, Map<String, Object> schema) {}

    /**
     * @param httpMethod upper-case verb
     * @param path path template exactly as declared, e.g. {@code /items/{sku}}
     * @param operationId declared id, or {@code "<verb> <path>"} when the document has none
     * @param hasOperationId whether the id was declared rather than synthesised
     * @param gatewayAuthScopes scopes from {@value #AUTH_SCOPES_EXTENSION}, empty when absent
     * @param summary the operation's summary or description, may be null
     * @param parameters path and query parameters, in declaration order
     * @param requestBodySchema JSON schema of the request body's JSON media type, or null
     * @param responseSchema JSON schema of the first 2xx response's JSON media type, or null
     * @param requestMediaTypes media types declared under the request body's {@code content},
     *     lower-case, in declaration order
     * @param responseMediaTypes media types declared under the first 2xx response's {@code
     *     content}, lower-case, in declaration order
     * @param requestSchemaName {@code components.schemas} name the request body schema refers to,
     *     or null when inline
     * @param responseSchemaName {@code components.schemas} name the response schema (or, for an
     *     array, its items) refers to, or null when inline
     */
    public record Operation(
            String httpMethod,
            String path,
            String operationId,
            boolean hasOperationId,
            List<String> gatewayAuthScopes,
            String summary,
            List<Parameter> parameters,
            Map<String, Object> requestBodySchema,
            Map<String, Object> responseSchema,
            List<String> requestMediaTypes,
            List<String> responseMediaTypes,
            String requestSchemaName,
            String responseSchemaName) {
        public Operation {
            gatewayAuthScopes = gatewayAuthScopes != null ? List.copyOf(gatewayAuthScopes) : List.of();
            parameters = parameters != null ? List.copyOf(parameters) : List.of();
            requestMediaTypes = requestMediaTypes != null ? List.copyOf(requestMediaTypes) : List.of();
            responseMediaTypes = responseMediaTypes != null ? List.copyOf(responseMediaTypes) : List.of();
        }

        /** Constructor without media-type information: JSON is assumed wherever a schema is present. */
        public Operation(
                String httpMethod,
                String path,
                String operationId,
                boolean hasOperationId,
                List<String> gatewayAuthScopes,
                String summary,
                List<Parameter> parameters,
                Map<String, Object> requestBodySchema,
                Map<String, Object> responseSchema) {
            this(
                    httpMethod,
                    path,
                    operationId,
                    hasOperationId,
                    gatewayAuthScopes,
                    summary,
                    parameters,
                    requestBodySchema,
                    responseSchema,
                    requestBodySchema != null ? List.of(MEDIA_JSON) : List.of(),
                    responseSchema != null ? List.of(MEDIA_JSON) : List.of(),
                    schemaName(requestBodySchema),
                    schemaName(responseSchema));
        }

        /** Convenience constructor without schema information. */
        public Operation(
                String httpMethod,
                String path,
                String operationId,
                boolean hasOperationId,
                List<String> gatewayAuthScopes) {
            this(httpMethod, path, operationId, hasOperationId, gatewayAuthScopes, null, List.of(), null, null);
        }
    }

    /**
     * Component name a schema refers to: {@code #/components/schemas/Item} → {@code Item}; for an
     * array schema, the name its {@code items} refer to. Null for inline schemas.
     */
    public static String schemaName(Map<String, Object> schema) {
        if (schema == null) return null;
        Object ref = schema.get("$ref");
        if (ref instanceof String r && r.startsWith(SCHEMA_REF_PREFIX)) return r.substring(SCHEMA_REF_PREFIX.length());
        if ("array".equals(schema.get("type")) && schema.get("items") instanceof Map<?, ?>) {
            return schemaName(asMap(schema.get("items")));
        }
        return null;
    }

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";

    private final List<Operation> operations;
    private final Map<String, Map<String, Object>> componentSchemas;

    private DownstreamOpenApiDocument(List<Operation> operations, Map<String, Map<String, Object>> componentSchemas) {
        this.operations = Collections.unmodifiableList(operations);
        this.componentSchemas = Collections.unmodifiableMap(componentSchemas);
    }

    public List<Operation> operations() {
        return operations;
    }

    /** {@code components.schemas} by name, as declared (may contain {@code $ref}s to each other). */
    public Map<String, Map<String, Object>> componentSchemas() {
        return componentSchemas;
    }

    /**
     * Picks the media type of a fetched document: the {@code Content-Type} header when it names
     * JSON or YAML, else a sniff of the first non-blank character ({@code {} = JSON).
     */
    public static String detectMediaType(byte[] raw, String contentTypeHeader) {
        if (contentTypeHeader != null) {
            String ct = contentTypeHeader.toLowerCase(Locale.ROOT);
            if (ct.contains("json")) return MEDIA_JSON;
            if (ct.contains("yaml") || ct.contains("yml")) return MEDIA_YAML;
        }
        for (byte b : raw) {
            if (b == ' '
                    || b == '\n'
                    || b == '\r'
                    || b == '\t'
                    || b == (byte) 0xEF
                    || b == (byte) 0xBB
                    || b == (byte) 0xBF) {
                continue;
            }
            return b == '{' ? MEDIA_JSON : MEDIA_YAML;
        }
        return MEDIA_JSON;
    }

    /**
     * @throws IllegalArgumentException when the bytes are not a parseable document or carry no
     *     {@code paths} object
     */
    @SuppressWarnings("unchecked")
    public static DownstreamOpenApiDocument parse(byte[] raw, String mediaType) {
        Object root;
        try {
            if (MEDIA_YAML.equals(mediaType)) {
                root = new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(raw, StandardCharsets.UTF_8));
            } else {
                root = JSON.readValue(raw, Map.class);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Downstream OpenAPI document is not valid " + mediaType + ": " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("Downstream OpenAPI document root is not an object");
        }
        Object paths = rootMap.get("paths");
        if (!(paths instanceof Map<?, ?> pathsMap)) {
            throw new IllegalArgumentException("Downstream OpenAPI document has no 'paths' object");
        }

        Map<String, Object> components = asMap(rootMap.get("components"));
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : asMap(components.get("schemas")).entrySet()) {
            schemas.put(e.getKey(), asMap(e.getValue()));
        }

        List<Operation> ops = new ArrayList<>();
        for (Map.Entry<?, ?> pathEntry : pathsMap.entrySet()) {
            String path = String.valueOf(pathEntry.getKey());
            if (!(pathEntry.getValue() instanceof Map<?, ?> item)) continue;
            List<Parameter> pathLevelParams = parameters(item.get("parameters"), components);
            for (Map.Entry<?, ?> opEntry : item.entrySet()) {
                String verb = String.valueOf(opEntry.getKey()).toLowerCase(Locale.ROOT);
                if (!VERBS.contains(verb)) continue;
                Map<String, Object> op = asMap(opEntry.getValue());
                String httpMethod = verb.toUpperCase(Locale.ROOT);
                Object idValue = op.get("operationId");
                boolean hasId = idValue instanceof String s && !s.isBlank();
                String operationId = hasId ? ((String) idValue).trim() : httpMethod + " " + path;

                List<Parameter> params = new ArrayList<>(pathLevelParams);
                for (Parameter p : parameters(op.get("parameters"), components)) {
                    params.removeIf(existing ->
                            existing.name().equals(p.name()) && existing.in().equals(p.in()));
                    params.add(p);
                }
                String summary = op.get("summary") instanceof String s && !s.isBlank()
                        ? s
                        : op.get("description") instanceof String d && !d.isBlank() ? d : null;

                Map<String, Object> requestContent = requestBodyContent(op.get("requestBody"), components);
                Map<String, Object> responseContent = responseContent(op.get("responses"), components);
                Map<String, Object> requestSchema = jsonSchema(requestContent);
                Map<String, Object> responseSchema = jsonSchema(responseContent);
                ops.add(new Operation(
                        httpMethod,
                        path,
                        operationId,
                        hasId,
                        scopes(op.get(AUTH_SCOPES_EXTENSION)),
                        summary,
                        params,
                        requestSchema,
                        responseSchema,
                        mediaTypes(requestContent),
                        mediaTypes(responseContent),
                        schemaName(requestSchema),
                        schemaName(responseSchema)));
            }
        }
        return new DownstreamOpenApiDocument(ops, schemas);
    }

    // -------------------------------------------------------------------------

    private static List<Parameter> parameters(Object raw, Map<String, Object> components) {
        List<Parameter> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            Map<String, Object> p = resolve(asMap(o), "parameters", components);
            String name = p.get("name") instanceof String s ? s : null;
            String in = p.get("in") instanceof String s ? s.toLowerCase(Locale.ROOT) : "query";
            if (name == null) continue;
            if (!in.equals("path") && !in.equals("query")) continue; // headers and cookies are not arguments
            boolean required = in.equals("path") || Boolean.TRUE.equals(p.get("required"));
            out.add(new Parameter(name, in, required, asMap(p.get("schema"))));
        }
        return out;
    }

    /** The request body's {@code content} map (media type → media type object), empty when there is no body. */
    private static Map<String, Object> requestBodyContent(Object raw, Map<String, Object> components) {
        if (raw == null) return Map.of();
        Map<String, Object> body = resolve(asMap(raw), "requestBodies", components);
        return asMap(body.get("content"));
    }

    /** The first 2xx (else {@code default}) response's {@code content} map, empty when it has no body. */
    private static Map<String, Object> responseContent(Object raw, Map<String, Object> components) {
        Map<String, Object> responses = asMap(raw);
        for (String code : List.of("200", "201", "202", "2XX", "default")) {
            Object r = responses.get(code);
            if (r == null) continue;
            Map<String, Object> response = resolve(asMap(r), "responses", components);
            Map<String, Object> content = asMap(response.get("content"));
            if (!content.isEmpty()) return content;
            if (!code.equals("default")) return Map.of(); // a 2xx with no body
        }
        return Map.of();
    }

    /** Declared media types, lower-cased and stripped of parameters, in declaration order. */
    private static List<String> mediaTypes(Map<String, Object> content) {
        List<String> out = new ArrayList<>();
        for (String key : content.keySet()) {
            int semi = key.indexOf(';');
            out.add((semi >= 0 ? key.substring(0, semi) : key).trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Picks the JSON media type's schema out of a {@code content} map, else the first media type's. */
    private static Map<String, Object> jsonSchema(Object rawContent) {
        Map<String, Object> content = asMap(rawContent);
        if (content.isEmpty()) return null;
        Map<String, Object> chosen = null;
        for (Map.Entry<String, Object> e : content.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).contains("json")) {
                chosen = asMap(e.getValue());
                break;
            }
        }
        if (chosen == null) chosen = asMap(content.values().iterator().next());
        Object schema = chosen.get("schema");
        return schema instanceof Map<?, ?> ? asMap(schema) : null;
    }

    /** Resolves a {@code $ref} into {@code components.<kind>}; other refs are returned unchanged. */
    private static Map<String, Object> resolve(Map<String, Object> node, String kind, Map<String, Object> components) {
        Object ref = node.get("$ref");
        if (!(ref instanceof String r)) return node;
        String prefix = "#/components/" + kind + "/";
        if (!r.startsWith(prefix)) return node;
        Map<String, Object> target = asMap(asMap(components.get(kind)).get(r.substring(prefix.length())));
        return target.isEmpty() ? node : target;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Accepts a list of strings or a comma/space separated string. */
    private static List<String> scopes(Object value) {
        if (value == null) return List.of();
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank())
                    out.add(String.valueOf(o).trim());
            }
        } else {
            for (String s : String.valueOf(value).split("[,\\s]+")) {
                if (!s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }
}
