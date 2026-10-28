package dev.appiaworks.shiguchi.avro;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroSchemaSource;
import dev.appiaworks.shiguchi.config.ServiceDefinition.AvroWireFormat;
import dev.appiaworks.shiguchi.rest.UpstreamBodyCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Speaks Avro to {@code REST_DOWNSTREAM} backends. Registered as the {@link UpstreamBodyCodec} for
 * {@code avro/binary} (also {@code application/avro} and {@code application/vnd.apache.avro+binary}).
 *
 * <p>Schemas come from the backend's OpenAPI document ({@code x-avro-schema} on a component
 * schema, inline JSON object or string), from {@code .avsc} files named in the service's {@code
 * avro.schema-files}, or both, per {@code avro.schema-source}. A component binds to a file schema
 * when its name equals the record's simple or full name, or when it carries {@code x-avro-record}
 * naming the record. Every component whose schema resolves is reported bound; the dispatcher only
 * prefers Avro for operations whose request or response schema is one of them.
 *
 * <p>Wire format per service from {@code avro.wire-format}: raw datum by default, single-object
 * encoding, or the Confluent wire format. Incoming bytes are recognised by their header regardless
 * of the setting.
 *
 * <p>With {@code avro.schema-registry-url} set, every bound schema is registered with that
 * Confluent-compatible registry when the service loads (subject from {@code avro.subject-strategy}
 * or {@code avro.subjects}), and every version the registry holds for those subjects is fetched,
 * so a backend writing an older or newer version is decoded by the id its bytes carry. Nothing
 * calls the registry per request: an id the load did not see is a decode error, and a reload
 * refreshes the cache.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.avro", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AvroUpstreamCodec implements UpstreamBodyCodec {

    private static final Logger log = LoggerFactory.getLogger(AvroUpstreamCodec.class);

    /** The media type sent and asked for. */
    public static final String AVRO_BINARY = "avro/binary";

    /** Vendor extension carrying an inline Avro schema on an OpenAPI component schema. */
    public static final String SCHEMA_EXTENSION = "x-avro-schema";

    /** Vendor extension naming a record (from {@code schema-files}) an OpenAPI component maps to. */
    public static final String RECORD_EXTENSION = "x-avro-record";

    private static final Set<String> ALIASES =
            Set.of(AVRO_BINARY, "application/avro", "application/vnd.apache.avro+binary", "application/avro+binary");

    private final AvroSchemaRegistry registry;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, AvroWireFormat> wireFormats = new ConcurrentHashMap<>();
    private final Map<String, RegistryBinding> registries = new ConcurrentHashMap<>();
    private volatile SchemaRegistryClients clients = SchemaRegistryClient::new;

    /** Makes registry clients; replaceable so tests can point services at a stub. */
    @FunctionalInterface
    public interface SchemaRegistryClients {
        SchemaRegistryClient create(String url, java.time.Duration timeout);
    }

    /** One service's registry client and the id each of its schemas was registered under. */
    private record RegistryBinding(SchemaRegistryClient client, Map<Schema, Integer> ids) {}

    public AvroUpstreamCodec(ResourceLoader resourceLoader) {
        this(new AvroSchemaRegistry(), resourceLoader);
    }

    public AvroUpstreamCodec(AvroSchemaRegistry registry, ResourceLoader resourceLoader) {
        this.registry = registry;
        this.resourceLoader = resourceLoader != null ? resourceLoader : new DefaultResourceLoader();
    }

    /** Codec with no Spring context: classpath and file resources only. */
    public AvroUpstreamCodec() {
        this(new AvroSchemaRegistry(), new DefaultResourceLoader());
    }

    public AvroSchemaRegistry registry() {
        return registry;
    }

    public void setSchemaRegistryClients(SchemaRegistryClients clients) {
        this.clients = clients != null ? clients : SchemaRegistryClient::new;
    }

    /** The registry client a service was bound to, if it configured one. */
    public java.util.Optional<SchemaRegistryClient> registryClient(String serviceName) {
        RegistryBinding b = registries.get(serviceName);
        return java.util.Optional.ofNullable(b != null ? b.client() : null);
    }

    @Override
    public boolean supports(String mediaType) {
        return mediaType != null && ALIASES.contains(mediaType.toLowerCase(Locale.ROOT));
    }

    @Override
    public String mediaType() {
        return AVRO_BINARY;
    }

    @Override
    public Set<String> registerService(
            String serviceName, ServiceDefinition definition, Map<String, Map<String, Object>> componentSchemas) {
        ServiceDefinition.AvroConfig cfg = definition != null && definition.getAvro() != null
                ? definition.getAvro()
                : new ServiceDefinition.AvroConfig();
        wireFormats.put(serviceName, cfg.getWireFormat() != null ? cfg.getWireFormat() : AvroWireFormat.BINARY);

        // Named types referenced across schemas resolve against everything parsed so far. A failed
        // parse can leave half-registered names in an Avro parser, so every attempt gets a fresh
        // parser seeded with the known types instead of one shared parser.
        List<Schema> known = new ArrayList<>();
        Map<String, Schema> fromFiles = new LinkedHashMap<>();
        if (cfg.getSchemaSource() != AvroSchemaSource.OPENAPI) {
            for (String location : cfg.getSchemaFiles()) {
                for (Schema s : parseFile(location, known)) {
                    fromFiles.put(s.getFullName(), s);
                    fromFiles.putIfAbsent(s.getName(), s);
                    known.add(s);
                }
            }
        }

        Map<String, Schema> bound = new LinkedHashMap<>();
        // Components pointing at file schemas by name or extension.
        for (Map.Entry<String, Map<String, Object>> e : componentSchemas.entrySet()) {
            Object ref = e.getValue().get(RECORD_EXTENSION);
            Schema s = ref instanceof String r ? fromFiles.get(r) : null;
            if (s == null) s = fromFiles.get(e.getKey());
            if (s != null) bound.put(e.getKey(), s);
        }
        // Components carrying their own schema. A schema may reference a record declared in another
        // component, so parse in passes until nothing new resolves.
        if (cfg.getSchemaSource() != AvroSchemaSource.FILES) {
            Map<String, String> pending = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : componentSchemas.entrySet()) {
                Object inline = e.getValue().get(SCHEMA_EXTENSION);
                if (inline == null || bound.containsKey(e.getKey())) continue;
                pending.put(e.getKey(), inline instanceof String s ? s : toJson(inline));
            }
            Map<String, String> failures = new LinkedHashMap<>();
            boolean progress = true;
            while (!pending.isEmpty() && progress) {
                progress = false;
                for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, String> e = it.next();
                    try {
                        Schema parsed = parserWith(known).parse(e.getValue());
                        bound.put(e.getKey(), parsed);
                        if (isNamed(parsed)) known.add(parsed);
                        it.remove();
                        progress = true;
                    } catch (AvroRuntimeException ex) {
                        failures.put(e.getKey(), ex.getMessage());
                    }
                }
            }
            for (String name : pending.keySet()) {
                log.warn(
                        "Component '{}' of service '{}' has an {} that does not parse: {}",
                        name,
                        serviceName,
                        SCHEMA_EXTENSION,
                        failures.get(name));
            }
        }

        registry.register(serviceName, bound);
        bindRegistry(serviceName, cfg, bound);
        return new LinkedHashSet<>(bound.keySet());
    }

    /**
     * Load-time registry work: register every bound schema and fetch every version of its subject.
     * A registry that is down fails the service load — the alternative, a codec that cannot frame
     * its bytes, would fail every request instead.
     */
    private void bindRegistry(String serviceName, ServiceDefinition.AvroConfig cfg, Map<String, Schema> bound) {
        String url = cfg.getSchemaRegistryUrl();
        if (url == null || url.isBlank()) {
            registries.remove(serviceName);
            if (cfg.getWireFormat() == AvroWireFormat.CONFLUENT) {
                throw new IllegalStateException("Service '" + serviceName
                        + "' sets avro.wire-format: CONFLUENT but no avro.schema-registry-url; the Confluent "
                        + "framing carries a registry id");
            }
            return;
        }
        SchemaRegistryClient client = clients.create(url, cfg.getRegistryTimeout());
        Map<Schema, Integer> ids = new java.util.IdentityHashMap<>();
        for (Map.Entry<String, Schema> e : bound.entrySet()) {
            Schema schema = e.getValue();
            if (ids.containsKey(schema) || !isNamed(schema)) continue;
            String subject = subjectFor(cfg, e.getKey(), schema);
            try {
                ids.put(schema, client.register(subject, schema));
                client.loadAllVersions(subject);
            } catch (RuntimeException ex) {
                throw new IllegalStateException(
                        "Schema registry " + url + " rejected subject '" + subject + "' of service '" + serviceName
                                + "': " + ex.getMessage(),
                        ex);
            }
        }
        registries.put(serviceName, new RegistryBinding(client, ids));
        log.info(
                "Service '{}': {} Avro schema(s) registered with {} ({} framing)",
                serviceName,
                ids.size(),
                url.replaceAll("//[^@/]+@", "//"),
                cfg.getWireFormat());
    }

    /** {@code avro.subjects} wins; else the configured strategy. */
    static String subjectFor(ServiceDefinition.AvroConfig cfg, String component, Schema schema) {
        String explicit = cfg.getSubjects() != null ? cfg.getSubjects().get(component) : null;
        if (explicit != null && !explicit.isBlank()) return explicit;
        return cfg.getSubjectStrategy() == ServiceDefinition.AvroSubjectStrategy.COMPONENT_NAME
                ? component
                : schema.getFullName();
    }

    @Override
    public void unregisterService(String serviceName) {
        registry.remove(serviceName);
        wireFormats.remove(serviceName);
        registries.remove(serviceName);
    }

    @Override
    public boolean canServe(String serviceName, String schemaName) {
        return registry.find(serviceName, schemaName).isPresent();
    }

    @Override
    public Object decode(String serviceName, String schemaName, boolean array, byte[] body) {
        Schema schema = schemaFor(serviceName, schemaName);
        Schema reader = array ? Schema.createArray(schema) : schema;
        RegistryBinding binding = registries.get(serviceName);
        Object datum = AvroFraming.decode(
                body,
                reader,
                fp -> registry.findByFingerprint(serviceName, fp)
                        .map(writer -> array ? Schema.createArray(writer) : writer),
                id -> binding == null
                        ? java.util.Optional.empty()
                        : binding.client().cachedById(id).map(writer -> array ? Schema.createArray(writer) : writer));
        return AvroMapConverter.toJava(datum, reader);
    }

    @Override
    public byte[] encode(String serviceName, String schemaName, Map<String, Object> body) {
        Schema schema = schemaFor(serviceName, schemaName);
        Object datum = AvroMapConverter.fromJava(body, schema);
        AvroWireFormat format = wireFormats.getOrDefault(serviceName, AvroWireFormat.BINARY);
        if (format == AvroWireFormat.CONFLUENT) {
            RegistryBinding binding = registries.get(serviceName);
            Integer id = binding != null ? binding.ids().get(schema) : null;
            if (id == null) {
                throw new IllegalStateException("Schema '" + schemaName + "' of service '" + serviceName
                        + "' was not registered with the schema registry, so it cannot be framed as CONFLUENT");
            }
            return AvroFraming.encodeConfluent(datum, schema, id);
        }
        return AvroFraming.encode(datum, schema, format);
    }

    private Schema schemaFor(String serviceName, String schemaName) {
        return registry.find(serviceName, schemaName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No Avro schema bound for '" + schemaName + "' of service '" + serviceName + "'"));
    }

    /** A parser that already knows {@code known}, so references to those names resolve. */
    private static Schema.Parser parserWith(List<Schema> known) {
        Schema.Parser parser = new Schema.Parser();
        if (!known.isEmpty()) parser.addTypes(known);
        return parser;
    }

    /** Parses one {@code .avsc}; a top-level JSON array yields every named type in it. */
    private List<Schema> parseFile(String location, List<Schema> known) {
        Resource resource = resourceLoader.getResource(location);
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read Avro schema file " + location + ": " + e.getMessage(), e);
        }
        Schema parsed;
        try {
            parsed = parserWith(known).parse(text);
        } catch (AvroRuntimeException e) {
            throw new IllegalArgumentException(
                    "Avro schema file " + location + " does not parse: " + e.getMessage(), e);
        }
        List<Schema> out = new ArrayList<>();
        if (parsed.getType() == Schema.Type.UNION) {
            for (Schema s : parsed.getTypes()) if (isNamed(s)) out.add(s);
        } else if (isNamed(parsed)) {
            out.add(parsed);
        }
        return out;
    }

    private static boolean isNamed(Schema s) {
        return s.getType() == Schema.Type.RECORD || s.getType() == Schema.Type.ENUM || s.getType() == Schema.Type.FIXED;
    }

    private String toJson(Object node) {
        try {
            return json.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalArgumentException(SCHEMA_EXTENSION + " is not serialisable: " + e.getMessage(), e);
        }
    }
}
