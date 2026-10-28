package dev.appiaworks.shiguchi.avro;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;
import org.apache.avro.SchemaNormalization;

/**
 * The Avro schemas known per {@code REST_DOWNSTREAM} service: by the name the OpenAPI document
 * uses for them (a {@code components.schemas} key), by the record's own full and simple names,
 * and by their 64-bit parsing fingerprint so single-object-encoded bytes can name their writer.
 *
 * <p>Replaced wholesale per service on every load or refresh, so a request never sees a
 * half-updated set.
 */
public class AvroSchemaRegistry {

    /** One service's bound schemas. */
    public record ServiceSchemas(Map<String, Schema> byName, Map<Long, Schema> byFingerprint) {
        public ServiceSchemas {
            byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
            byFingerprint = Collections.unmodifiableMap(new LinkedHashMap<>(byFingerprint));
        }
    }

    private final Map<String, ServiceSchemas> services = new ConcurrentHashMap<>();

    /**
     * Binds {@code schemasByName} (OpenAPI component name → schema) for a service. Every named
     * record is additionally reachable by its Avro full name and simple name, and by fingerprint.
     */
    public void register(String serviceName, Map<String, Schema> schemasByName) {
        Map<String, Schema> byName = new LinkedHashMap<>(schemasByName);
        Map<Long, Schema> byFingerprint = new LinkedHashMap<>();
        for (Schema schema : schemasByName.values()) {
            if (schema.getType() == Schema.Type.RECORD || schema.getType() == Schema.Type.ENUM) {
                byName.putIfAbsent(schema.getFullName(), schema);
                byName.putIfAbsent(schema.getName(), schema);
            }
            byFingerprint.putIfAbsent(fingerprint(schema), schema);
        }
        services.put(serviceName, new ServiceSchemas(byName, byFingerprint));
    }

    public void remove(String serviceName) {
        services.remove(serviceName);
    }

    public Optional<Schema> find(String serviceName, String schemaName) {
        ServiceSchemas s = services.get(serviceName);
        return s == null || schemaName == null
                ? Optional.empty()
                : Optional.ofNullable(s.byName().get(schemaName));
    }

    public Optional<Schema> findByFingerprint(String serviceName, long fingerprint) {
        ServiceSchemas s = services.get(serviceName);
        return s == null
                ? Optional.empty()
                : Optional.ofNullable(s.byFingerprint().get(fingerprint));
    }

    /** Names bound for a service, as registered (component names first, then record names). */
    public Set<String> names(String serviceName) {
        ServiceSchemas s = services.get(serviceName);
        return s == null ? Set.of() : s.byName().keySet();
    }

    /** CRC-64-AVRO parsing fingerprint, the one single-object encoding carries. */
    public static long fingerprint(Schema schema) {
        return SchemaNormalization.parsingFingerprint64(schema);
    }
}
