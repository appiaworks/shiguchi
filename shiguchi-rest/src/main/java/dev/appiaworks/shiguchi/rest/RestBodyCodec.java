package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.Map;

/**
 * A non-JSON body encoding the gateway's own REST surface can accept from and return to
 * callers, for routes that front gRPC services. Beans of this type are collected by the REST
 * filter; JSON needs no codec.
 *
 * <p>The filter picks the request codec from the request's {@code Content-Type} and the response
 * codec from {@code Accept} (highest quality first; JSON and wildcards mean JSON). A codec works
 * on the same plain {@code Map} the JSON path uses: the decoded request Map is merged with path
 * variables and query parameters and bound to the proto request exactly like parsed JSON, and the
 * response Map is what the serde tiers produce from the proto response. Only unary routes take
 * part; server-streaming and merge routes stay NDJSON / JSON.
 *
 * <p>Schemas are derived from the proto descriptors. A codec may publish them: {@link
 * #openApiSchemaExtensions} adds vendor extensions next to its media type in the generated
 * OpenAPI document (the Avro codec adds {@code x-avro-schema}), and {@link #responseHeaders} adds
 * headers to every response it encodes (the Avro codec adds the schema fingerprint).
 */
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.14.0")
public interface RestBodyCodec {

    /** The media type this codec answers with and lists in OpenAPI, e.g. {@code avro/binary}. */
    String mediaType();

    /** Whether this codec reads and writes {@code mediaType} (bare, lower-case, no parameters). */
    boolean supports(String mediaType);

    /**
     * Decodes a request body into the Map shape {@code SerdeSelector.deserializeFromMap} binds.
     *
     * @throws IllegalArgumentException when the bytes do not decode as {@code requestType}; the
     *     filter answers 400
     */
    Map<String, Object> decode(byte[] body, Descriptor requestType);

    /** Encodes a response Map (from {@code SerdeSelector.serializeToMap}) as {@code responseType}. */
    byte[] encode(Map<String, Object> response, Descriptor responseType);

    /** Extra response headers for a body of {@code responseType}, e.g. a schema fingerprint. */
    default Map<String, String> responseHeaders(Descriptor responseType) {
        return Map.of();
    }

    /** Vendor extensions to put on this media type's OpenAPI entry for {@code type}. */
    default Map<String, Object> openApiSchemaExtensions(Descriptor type) {
        return Map.of();
    }
}
