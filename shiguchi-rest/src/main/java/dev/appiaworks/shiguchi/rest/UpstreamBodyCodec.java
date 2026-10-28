package dev.appiaworks.shiguchi.rest;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.Map;
import java.util.Set;

/**
 * A non-JSON body encoding the gateway can speak to a {@code REST_DOWNSTREAM} backend, such as
 * Avro. Beans of this type are collected by {@link HttpUpstreamDispatcher}; the JSON path needs no
 * codec.
 *
 * <p>Life cycle. When a backend's OpenAPI document is loaded (and on every refresh) the route
 * loader calls {@link #registerService} with the document's {@code components.schemas}; the codec
 * keeps whatever it derives from them, keyed by service and component name, and reports which
 * components it can serve. At request time the dispatcher asks {@link #canServe} before preferring
 * the codec's media type for an operation, then {@link #decode}s the response by the {@code
 * Content-Type} the backend actually returned, and {@link #encode}s request bodies when the
 * preferred type is one of the operation's declared request media types.
 *
 * <p>Decoded values are plain Java: {@code Map} for records, {@code List} for arrays, {@code
 * String}/{@code Long}/{@code Double}/{@code Boolean} scalars, base64 strings for bytes, ISO-8601
 * strings for temporal logical types. The dispatcher binds that Map through the synthetic
 * response message exactly as it binds parsed JSON, so the shape seen above is identical.
 */
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.4.0")
public interface UpstreamBodyCodec {

    /** Whether this codec reads and writes {@code mediaType} (bare type, lower-case, no parameters). */
    boolean supports(String mediaType);

    /**
     * The media type this codec sends and asks for, e.g. {@code avro/binary}. Used as the {@code
     * Accept} and {@code Content-Type} value; {@link #supports} may accept aliases.
     */
    String mediaType();

    /**
     * Learns the schemas of one service. Called at load time and again on refresh with the whole
     * document; earlier state for that service is replaced.
     *
     * @param serviceName the service's configured name
     * @param definition the service's configuration (codec-specific settings live there)
     * @param componentSchemas the document's {@code components.schemas}, as declared
     * @return the component names this codec can now encode and decode for the service
     */
    Set<String> registerService(
            String serviceName, ServiceDefinition definition, Map<String, Map<String, Object>> componentSchemas);

    /** Forgets a service's schemas (the service was removed from the configuration). */
    default void unregisterService(String serviceName) {}

    /** Whether {@link #registerService} bound a schema for {@code schemaName} of {@code serviceName}. */
    boolean canServe(String serviceName, String schemaName);

    /**
     * Decodes a response body.
     *
     * @param serviceName the service the bytes came from
     * @param schemaName the operation's response schema component (for an array, its items)
     * @param array whether the body is an array of that schema rather than one record
     * @param body the raw response bytes
     * @return a record as a {@code Map}, or an array as a {@code List}
     * @throws IllegalArgumentException when the bytes do not decode with the bound schema
     */
    Object decode(String serviceName, String schemaName, boolean array, byte[] body);

    /**
     * Encodes a request body.
     *
     * @param serviceName the service the request goes to
     * @param schemaName the operation's request body schema component
     * @param body the record as a {@code Map} (already re-keyed to the backend's property names)
     * @throws IllegalArgumentException when the value does not fit the bound schema
     */
    byte[] encode(String serviceName, String schemaName, Map<String, Object> body);
}
