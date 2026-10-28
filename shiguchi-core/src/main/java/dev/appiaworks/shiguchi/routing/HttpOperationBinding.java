package dev.appiaworks.shiguchi.routing;

import java.util.List;
import java.util.Objects;

/**
 * How one synthetic method of a {@code REST_DOWNSTREAM} service maps onto an HTTP call: the
 * verb, the backend path template, which fields of the synthetic request message travel in the
 * path, the query string, or the body, and which media types the operation advertises.
 *
 * <p>Field names are proto field names of the synthetic request message. The dispatcher turns
 * GraphQL arguments (JSON names) into those before building the request.
 *
 * <p>The media types and schema names are what upstream body codecs negotiate on: an operation
 * whose response lists {@code avro/binary} next to JSON, and whose response schema is a component
 * a codec has a schema for, can be fetched in that encoding instead of JSON. Schema names are
 * {@code components.schemas} keys of the backend's own document (not the namespaced names the
 * merged OpenAPI document shows), or null when the schema is inline.
 *
 * @param httpMethod upper-case verb
 * @param pathTemplate backend path with {@code {param}} placeholders, without the gateway prefix
 * @param pathParams request fields substituted into the path
 * @param queryParams request fields sent as query parameters when present
 * @param hasBody whether the remaining request fields are sent as a request body
 * @param responseIsArray whether the backend returns an array, wrapped into the synthetic
 *     response message's {@code items} field
 * @param responseIsScalar whether the backend returns a bare scalar, wrapped into the synthetic
 *     response message's {@code value} field
 * @param requestMediaTypes media types the operation accepts for its request body, as declared,
 *     lower-case, in declaration order; empty when the operation has no body
 * @param responseMediaTypes media types the operation's first 2xx response is declared in,
 *     lower-case, in declaration order; empty when it has no body
 * @param requestSchemaName component name of the request body schema, or null
 * @param responseSchemaName component name of the response schema (for an array response, of
 *     its items), or null
 */
public record HttpOperationBinding(
        String httpMethod,
        String pathTemplate,
        List<String> pathParams,
        List<String> queryParams,
        boolean hasBody,
        boolean responseIsArray,
        boolean responseIsScalar,
        List<String> requestMediaTypes,
        List<String> responseMediaTypes,
        String requestSchemaName,
        String responseSchemaName) {

    /** Field name holding an array response. */
    public static final String ARRAY_FIELD = "items";

    /** Field name holding a scalar response. */
    public static final String SCALAR_FIELD = "value";

    public HttpOperationBinding {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(pathTemplate, "pathTemplate");
        pathParams = pathParams != null ? List.copyOf(pathParams) : List.of();
        queryParams = queryParams != null ? List.copyOf(queryParams) : List.of();
        requestMediaTypes = requestMediaTypes != null ? List.copyOf(requestMediaTypes) : List.of();
        responseMediaTypes = responseMediaTypes != null ? List.copyOf(responseMediaTypes) : List.of();
    }

    /** Binding for a JSON-only operation with no schema names: the shape before media-type negotiation existed. */
    public HttpOperationBinding(
            String httpMethod,
            String pathTemplate,
            List<String> pathParams,
            List<String> queryParams,
            boolean hasBody,
            boolean responseIsArray,
            boolean responseIsScalar) {
        this(
                httpMethod,
                pathTemplate,
                pathParams,
                queryParams,
                hasBody,
                responseIsArray,
                responseIsScalar,
                hasBody ? List.of("application/json") : List.of(),
                List.of("application/json"),
                null,
                null);
    }

    /** Whether the operation declares {@code mediaType} (case-insensitive, parameters ignored) for its request body. */
    public boolean acceptsRequest(String mediaType) {
        return contains(requestMediaTypes, mediaType);
    }

    /** Whether the operation declares {@code mediaType} (case-insensitive, parameters ignored) for its response. */
    public boolean producesResponse(String mediaType) {
        return contains(responseMediaTypes, mediaType);
    }

    private static boolean contains(List<String> declared, String mediaType) {
        if (mediaType == null) return false;
        String wanted = bare(mediaType);
        for (String d : declared) if (bare(d).equals(wanted)) return true;
        return false;
    }

    /** {@code Application/JSON; charset=utf-8} → {@code application/json}. */
    public static String bare(String mediaType) {
        int semi = mediaType.indexOf(';');
        return (semi >= 0 ? mediaType.substring(0, semi) : mediaType).trim().toLowerCase(java.util.Locale.ROOT);
    }
}
