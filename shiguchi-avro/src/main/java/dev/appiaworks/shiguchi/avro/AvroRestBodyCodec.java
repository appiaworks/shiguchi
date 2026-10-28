package dev.appiaworks.shiguchi.avro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.shiguchi.config.AvroSurfaceConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import dev.appiaworks.shiguchi.rest.RestBodyCodec;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.avro.Schema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Avro on the gateway's own REST surface: a caller sending {@code Content-Type: avro/binary} to a
 * gRPC-backed route has the body decoded with the schema derived from the proto request message,
 * and one sending {@code Accept: avro/binary} gets the response encoded with the schema derived
 * from the proto response message. Schemas come from {@link ProtoToAvroSchema}; callers fetch
 * them from the schema endpoint and every Avro response carries {@code X-Avro-Schema-Fingerprint}.
 *
 * <p>Framing per {@code shiguchi.avro.framing}: single-object by default, so a caller can
 * check the fingerprint against the schema it holds; raw datum otherwise; or Confluent, when
 * {@code shiguchi.avro.schema-registry-url} names a registry the derived schemas are
 * registered with (subject = the record's full name) by {@link AvroRegistryRegistrar} once the
 * routes are loaded. Requests are recognised by their bytes whichever framing is configured.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.avro", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AvroRestBodyCodec implements RestBodyCodec {

    /** Header carrying the response schema's CRC-64-AVRO fingerprint, 16 hex digits. */
    public static final String FINGERPRINT_HEADER = "X-Avro-Schema-Fingerprint";

    public static final String AVRO_BINARY = AvroUpstreamCodec.AVRO_BINARY;

    private static final Set<String> ALIASES =
            Set.of(AVRO_BINARY, "application/avro", "application/vnd.apache.avro+binary", "application/avro+binary");

    private final ProtoToAvroSchema schemas;
    private final AvroWireFormat framing;
    private final ObjectMapper json = new ObjectMapper();
    private volatile SchemaRegistryClient registry;

    @org.springframework.beans.factory.annotation.Autowired
    public AvroRestBodyCodec(GatewayProperties properties) {
        AvroSurfaceConfig cfg =
                properties != null && properties.getAvro() != null ? properties.getAvro() : new AvroSurfaceConfig();
        this.schemas = new ProtoToAvroSchema(cfg.getUint64As());
        this.framing = cfg.getFraming() != null ? cfg.getFraming() : AvroWireFormat.SINGLE_OBJECT;
        String url = cfg.getSchemaRegistryUrl();
        if (url != null && !url.isBlank()) {
            this.registry = new SchemaRegistryClient(url, cfg.getRegistryTimeout(), null);
        } else if (this.framing == AvroWireFormat.CONFLUENT) {
            throw new IllegalStateException("shiguchi.avro.framing: CONFLUENT needs shiguchi.avro."
                    + "schema-registry-url; the Confluent framing carries a registry id");
        }
    }

    public AvroRestBodyCodec(ProtoToAvroSchema schemas, AvroWireFormat framing) {
        this(schemas, framing, null);
    }

    public AvroRestBodyCodec(ProtoToAvroSchema schemas, AvroWireFormat framing, SchemaRegistryClient registry) {
        this.schemas = schemas;
        this.framing = framing != null ? framing : AvroWireFormat.SINGLE_OBJECT;
        this.registry = registry;
        if (this.framing == AvroWireFormat.CONFLUENT && registry == null) {
            throw new IllegalStateException("CONFLUENT framing needs a schema registry client");
        }
    }

    public ProtoToAvroSchema schemas() {
        return schemas;
    }

    /** The registry client, when {@code schema-registry-url} is set. */
    public java.util.Optional<SchemaRegistryClient> registryClient() {
        return java.util.Optional.ofNullable(registry);
    }

    /**
     * Registers the schemas derived from {@code types} with the registry and fetches every version
     * of their subjects. Load time only — called once the routes are known and after a reload.
     * Without a registry this is a no-op.
     *
     * @return how many schemas were registered
     */
    public int registerSchemas(java.util.Collection<Descriptor> types) {
        SchemaRegistryClient client = registry;
        if (client == null) return 0;
        int count = 0;
        for (Descriptor type : types) {
            Schema schema = schemas.schemaFor(type);
            String subject = schema.getFullName();
            client.register(subject, schema);
            client.loadAllVersions(subject);
            count++;
        }
        return count;
    }

    @Override
    public String mediaType() {
        return AVRO_BINARY;
    }

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && ALIASES.contains(mediaType.toLowerCase(Locale.ROOT));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> decode(byte[] body, Descriptor requestType) {
        Schema schema = schemas.schemaFor(requestType);
        SchemaRegistryClient client = registry;
        Object datum = AvroFraming.decode(
                body,
                schema,
                schemas::byFingerprint,
                id -> client == null ? java.util.Optional.empty() : client.cachedById(id));
        return (Map<String, Object>) AvroMapConverter.toJava(datum, schema);
    }

    @Override
    public byte[] encode(Map<String, Object> response, Descriptor responseType) {
        Schema schema = schemas.schemaFor(responseType);
        Object datum = AvroMapConverter.fromJava(response, schema);
        if (framing == AvroWireFormat.CONFLUENT) {
            int id = registry.cachedId(schema.getFullName(), schema)
                    .orElseThrow(() -> new IllegalStateException("Avro schema " + schema.getFullName()
                            + " is not registered with the schema registry; the CONFLUENT framing needs its id"));
            return AvroFraming.encodeConfluent(datum, schema, id);
        }
        return AvroFraming.encode(datum, schema, framing);
    }

    @Override
    public Map<String, String> responseHeaders(Descriptor responseType) {
        return Map.of(FINGERPRINT_HEADER, fingerprintHex(schemas.schemaFor(responseType)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> openApiSchemaExtensions(Descriptor type) {
        try {
            return Map.of(
                    AvroUpstreamCodec.SCHEMA_EXTENSION,
                    json.readValue(schemas.schemaFor(type).toString(), Map.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static String fingerprintHex(Schema schema) {
        return String.format("%016x", AvroSchemaRegistry.fingerprint(schema));
    }
}
