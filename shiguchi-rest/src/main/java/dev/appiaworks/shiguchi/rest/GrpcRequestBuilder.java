package dev.appiaworks.shiguchi.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RestConfig;
import dev.appiaworks.shiguchi.routing.ConventionArgMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the proto request JSON from a mix of path variables, query parameters,
 * and request body, applying convention-based pagination/sorting/mask injection.
 *
 * Convention detection (by field name, no proto annotation required):
 *  - "page_request"  → maps ?pageSize and ?pageToken query params
 *  - "sort_fields"   → maps ?sort=field:asc,field:desc
 *  - "read_mask"     → maps ?fields=id,title (or the X-Fields header) on the REST surface;
 *                      populated from the selection set on the GraphQL surface
 *
 * Relay cursor unpacking: if an "after" param is present, it is base64-decoded and
 * the pageToken component (before ":") is used as page_request.page_token.
 *
 * Uses a self-contained Jackson 2 ObjectMapper instance to remain compatible with
 * both Spring Boot 3 (Jackson 2) and Spring Boot 4 (Jackson 3) deployments.
 */
@Component
public class GrpcRequestBuilder {

    // Jackson 2 ObjectMapper — not sourced from the Spring context so that this class
    // works unchanged whether the application uses Jackson 2 (SB3) or Jackson 3 (SB4).
    // ObjectMapper is thread-safe for all read/write operations.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Query params carrying paging/sorting conventions, handed to {@link ConventionArgMapper}. */
    private static final List<String> CONVENTION_PARAMS = List.of("pageSize", "first", "pageToken", "after", "sort");

    /** {@code rest.request-validation.reject-conflicting-values}. */
    private final boolean rejectConflictingValues;

    /** {@code rest.request-validation.case-insensitive-enums}. */
    private final boolean caseInsensitiveEnums;

    /** Neither optional check, the historical behaviour; what tests and standalone callers use. */
    public GrpcRequestBuilder() {
        this(false, false);
    }

    /** The checks {@code rest.request-validation} turns on, when gateway properties exist. */
    @Autowired
    public GrpcRequestBuilder(ObjectProvider<GatewayProperties> properties) {
        this(
                validation(properties) != null && validation(properties).isRejectConflictingValues(),
                validation(properties) != null && validation(properties).isCaseInsensitiveEnums());
    }

    public GrpcRequestBuilder(boolean rejectConflictingValues, boolean caseInsensitiveEnums) {
        this.rejectConflictingValues = rejectConflictingValues;
        this.caseInsensitiveEnums = caseInsensitiveEnums;
    }

    /** Whether a path or query value that contradicts the body is refused rather than taking precedence. */
    public boolean rejectsConflictingValues() {
        return rejectConflictingValues;
    }

    /** Whether an enum value name matches ignoring case. */
    public boolean caseInsensitiveEnums() {
        return caseInsensitiveEnums;
    }

    private static RestConfig.RequestValidationConfig validation(ObjectProvider<GatewayProperties> properties) {
        GatewayProperties p = properties == null ? null : properties.getIfAvailable();
        return p == null || p.getRest() == null ? null : p.getRest().getRequestValidation();
    }

    /** Legacy JSON variant of {@link #buildRequestMap} — kept for callers that need a String. */
    public String buildRequestJson(
            Descriptor inputDescriptor, Map<String, String> pathVars, Map<String, String> queryParams, String body)
            throws Exception {
        return MAPPER.writeValueAsString(buildRequestMap(inputDescriptor, pathVars, queryParams, body));
    }

    /**
     * Merges path variables, query parameters, and the request body into a request Map that
     * {@code SerdeSelector.deserializeFromMap} consumes directly — no intermediate JSON string,
     * so the body is parsed exactly once per request.
     */
    public Map<String, Object> buildRequestMap(
            Descriptor inputDescriptor, Map<String, String> pathVars, Map<String, String> queryParams, String body)
            throws Exception {
        return buildRequestMap(inputDescriptor, pathVars, queryParams, body, null);
    }

    /**
     * {@link #buildRequestMap(Descriptor, Map, Map, String)} honouring the {@code body} of the
     * route's {@code google.api.http} rule: a field name maps the whole HTTP body onto that
     * top-level request field; {@code "*"} or null maps it onto the request message itself.
     */
    public Map<String, Object> buildRequestMap(
            Descriptor inputDescriptor,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            String body,
            String bodyField)
            throws Exception {

        Map<String, Object> merged = new HashMap<>();

        // 1. Start with body — stream JSON directly into merged, avoiding intermediate Map allocation
        if (body != null && !body.isBlank() && !body.equals("{}")) {
            if (isNamedBodyField(bodyField)) {
                merged.put(bodyField, MAPPER.readValue(body, Object.class));
            } else {
                MAPPER.readerForUpdating(merged).readValue(body);
            }
        }
        return overlay(merged, inputDescriptor, pathVars, queryParams);
    }

    /** True when the rule maps the body onto one request field rather than the whole message. */
    public static boolean isNamedBodyField(String bodyField) {
        return bodyField != null && !bodyField.isBlank() && !"*".equals(bodyField);
    }

    /**
     * Same as {@link #buildRequestMap} for a body a {@link
     * RestBodyCodec} already decoded: the Map is copied, then path variables, query parameters and
     * the convention fields are overlaid exactly as for JSON.
     */
    public Map<String, Object> buildRequestMapFromDecoded(
            Descriptor inputDescriptor,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            Map<String, Object> decodedBody) {
        return buildRequestMapFromDecoded(inputDescriptor, pathVars, queryParams, decodedBody, null);
    }

    /** {@link #buildRequestMapFromDecoded(Descriptor, Map, Map, Map)} honouring the rule's {@code body} field. */
    public Map<String, Object> buildRequestMapFromDecoded(
            Descriptor inputDescriptor,
            Map<String, String> pathVars,
            Map<String, String> queryParams,
            Map<String, Object> decodedBody,
            String bodyField) {
        Map<String, Object> merged = new HashMap<>();
        if (decodedBody != null) {
            if (isNamedBodyField(bodyField)) {
                merged.put(bodyField, new HashMap<>(decodedBody));
            } else {
                merged.putAll(decodedBody);
            }
        }
        return overlay(merged, inputDescriptor, pathVars, queryParams);
    }

    private Map<String, Object> overlay(
            Map<String, Object> merged,
            Descriptor inputDescriptor,
            Map<String, String> pathVars,
            Map<String, String> queryParams) {

        // 2. Overlay query params (excluding pagination/sort which get special handling). A
        //    dotted name addresses a nested field (?book.title=), as the HTTP rule spec reads it.
        //    With reject-conflicting-values, a value that contradicts what the body already set is
        //    collected instead of silently winning; the same value twice is fine. A repeated
        //    scalar field takes every value of its name (?tags=a&tags=b), one element each.
        List<RequestMapChecks.Conflict> conflicts = rejectConflictingValues ? new ArrayList<>() : null;
        for (var entry : queryParams.entrySet()) {
            String key = entry.getKey();
            if (isGatewayParam(key)) continue;
            Object value = entry.getValue();
            if (isRepeatedScalar(RequestMapChecks.resolve(inputDescriptor, key))) {
                value = QueryParameters.all(queryParams, key);
            }
            if (conflicts != null && RequestMapChecks.conflicts(merged, inputDescriptor, key, value)) {
                conflicts.add(new RequestMapChecks.Conflict(key, "query parameter"));
            }
            putPath(merged, key, value);
        }

        // 3. Overlay path variables (highest precedence); {book.id} nests the same way.
        for (var entry : pathVars.entrySet()) {
            if (conflicts != null
                    && RequestMapChecks.conflicts(merged, inputDescriptor, entry.getKey(), entry.getValue())) {
                conflicts.add(new RequestMapChecks.Conflict(entry.getKey(), "path variable"));
            }
            putPath(merged, entry.getKey(), entry.getValue());
        }
        if (conflicts != null && !conflicts.isEmpty()) {
            throw RequestMapChecks.conflictFailure(conflicts);
        }

        // 4/5. Fold the paging and sorting query params onto their nested convention fields.
        //      Shared with the GraphQL surface so both produce the same request shape.
        for (String key : CONVENTION_PARAMS) {
            String value = queryParams.get(key);
            if (value != null) merged.put(key, value);
        }
        merged = ConventionArgMapper.applyPaging(merged, inputDescriptor);
        merged = ConventionArgMapper.applySorting(merged, inputDescriptor);

        // 6. Inject read_mask from ?fields= (the X-Fields header is folded into queryParams
        //    upstream, so both spellings arrive here as the same "fields" key)
        FieldDescriptor maskField = inputDescriptor.findFieldByName(ConventionArgMapper.READ_MASK_FIELD);
        if (maskField != null) {
            List<String> paths = parseFieldPaths(queryParams.get("fields"));
            if (!paths.isEmpty()) {
                merged.put("readMask", Map.of("paths", paths));
            }
        }

        // 7. With case-insensitive-enums, "red" becomes the declared "RED" wherever an enum sits.
        if (caseInsensitiveEnums) {
            RequestMapChecks.normalizeEnums(merged, inputDescriptor);
        }

        return merged;
    }

    /** A repeated field a query string can fill: scalars and enums, not messages or maps. */
    private static boolean isRepeatedScalar(FieldDescriptor field) {
        return field != null
                && field.isRepeated()
                && !field.isMapField()
                && field.getJavaType() != FieldDescriptor.JavaType.MESSAGE;
    }

    /**
     * Writes {@code value} at a dotted path, creating (or descending into) nested maps; a
     * non-map value already sitting at an intermediate segment is replaced.
     */
    @SuppressWarnings("unchecked")
    static void putPath(Map<String, Object> target, String dottedKey, Object value) {
        int dot = dottedKey.indexOf('.');
        if (dot < 0) {
            target.put(dottedKey, value);
            return;
        }
        Map<String, Object> cursor = target;
        String[] segments = dottedKey.split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            Object existing = cursor.get(segments[i]);
            if (!(existing instanceof Map<?, ?>)) {
                existing = new HashMap<String, Object>();
                cursor.put(segments[i], existing);
            }
            cursor = (Map<String, Object>) existing;
        }
        cursor.put(segments[segments.length - 1], value);
    }

    /**
     * Parses a {@code ?fields=} value into FieldMask paths.
     *
     * <p>Accepts both JSON camelCase and proto snake_case segments and normalises to
     * snake_case, matching what {@code GraphQlFieldMaskInjector} produces — so a backend sees
     * one spelling regardless of which surface the request arrived on. Dotted paths are
     * normalised segment-wise ({@code author.displayName} → {@code author.display_name}).
     */
    static List<String> parseFieldPaths(String fieldsParam) {
        if (fieldsParam == null || fieldsParam.isBlank()) return List.of();

        List<String> paths = new ArrayList<>();
        for (String raw : fieldsParam.split(",")) {
            String path = raw.trim();
            if (path.isEmpty()) continue;

            StringBuilder normalised = new StringBuilder(path.length() + 4);
            for (String segment : path.split("\\.", -1)) {
                String trimmed = segment.trim();
                if (trimmed.isEmpty()) {
                    normalised.setLength(0); // malformed path (".." or trailing ".") — drop it
                    break;
                }
                if (!normalised.isEmpty()) normalised.append('.');
                normalised.append(toSnakeCase(trimmed));
            }
            if (!normalised.isEmpty() && !paths.contains(normalised.toString())) {
                paths.add(normalised.toString());
            }
        }
        return paths;
    }

    /** camelCase → snake_case; input that is already snake_case passes through unchanged. */
    private static String toSnakeCase(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && name.charAt(i - 1) != '_') out.append('_');
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Query params the gateway consumes itself (paging, sorting, projection) rather than binding to a field. */
    static boolean isGatewayParam(String key) {
        return switch (key) {
            case "pageSize", "pageToken", "first", "after", "sort", "fields" -> true;
            default -> false;
        };
    }
}
