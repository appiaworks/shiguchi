package dev.appiaworks.shiguchi.config;

import lombok.Data;

/**
 * How the REST surface speaks Avro to callers of gRPC-backed routes: a caller sending
 * {@code Content-Type: avro/binary} or {@code Accept: avro/binary} gets the request decoded /
 * the response encoded with a schema derived from the proto descriptor.
 */
@Data
public class AvroSurfaceConfig {
    /** Master switch for the Avro codec beans; ignored without the Avro starter. */
    private boolean enabled = true;

    /** How responses are framed. Single-object by default so callers can check the schema. */
    private ServiceDefinition.AvroWireFormat framing = ServiceDefinition.AvroWireFormat.SINGLE_OBJECT;

    /** Path under which derived schemas are served: {@code GET <endpoint>/{proto full name | fingerprint}}. */
    private String schemaEndpoint = "/avro/schemas";

    /** How {@code uint64} / {@code fixed64} map: Avro {@code long} (wraps above 2^63) or a decimal string. */
    private Uint64Mapping uint64As = Uint64Mapping.LONG;

    /**
     * Confluent-compatible schema registry the derived schemas are registered with, subject =
     * the record's full name. Required for {@code framing: CONFLUENT}; the registration runs
     * when the application is ready and after every schema reload, never per request.
     */
    private String schemaRegistryUrl;

    private java.time.Duration registryTimeout = java.time.Duration.ofSeconds(10);

    public enum Uint64Mapping {
        LONG,
        STRING
    }
}
