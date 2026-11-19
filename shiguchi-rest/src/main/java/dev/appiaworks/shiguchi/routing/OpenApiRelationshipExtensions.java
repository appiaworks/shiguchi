package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The GraphQL relationship declarations a REST_DOWNSTREAM OpenAPI document carries as
 * {@code x-shiguchi-*} vendor extensions:
 *
 * <ul>
 *   <li>{@value #REFERENCES} on a schema property: many-to-one to the named schema, with an optional
 *       {@value #FIELD} for the companion field's name;
 *   <li>{@value #HAS_MANY} on a schema property: one-to-many, {@code {type, group-by, field}};
 *   <li>{@value #BATCH_KEY} on a schema: the property its batch loader keys on;
 *   <li>{@value #LOADER} on an operation: a batch loader, the keys of a {@code rest-batch-loaders[]}
 *       entry (the endpoint is the operation's path).
 * </ul>
 *
 * Type names are OpenAPI schema names; the results carry the synthetic message names, that is the
 * service's {@code graphql-type-prefix} plus the sanitised schema name. A malformed or dangling
 * declaration is not returned: it is reported in {@link Result#problems()} with its location.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public final class OpenApiRelationshipExtensions {

    public static final String REFERENCES = "x-shiguchi-references";
    public static final String FIELD = "x-shiguchi-field";
    public static final String HAS_MANY = "x-shiguchi-has-many";
    public static final String BATCH_KEY = "x-shiguchi-batch-key";
    public static final String LOADER = "x-shiguchi-loader";

    private OpenApiRelationshipExtensions() {}

    /** A many-to-one: {@code property} of {@code parentType} holds the id of a {@code targetType}. */
    public record Reference(String parentType, String property, String targetType, String field, String location) {}

    /** A one-to-many: {@code property} of {@code parentType} is the id the {@code targetType}s group by. */
    public record HasMany(
            String parentType, String property, String targetType, String groupBy, String field, String location) {}

    /** The batch key of {@code type}. */
    public record Key(String type, String property, String location) {}

    /** A batch loader declared on an operation, its {@code type} already the synthetic message name. */
    public record Loader(RestBatchLoaderConfig config, String location) {}

    /** What a document declares, plus the problems found while reading it. */
    public record Result(
            List<Reference> references,
            List<HasMany> hasMany,
            List<Key> keys,
            List<Loader> loaders,
            List<String> problems) {
        public static final Result EMPTY = new Result(List.of(), List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return references.isEmpty() && hasMany.isEmpty() && keys.isEmpty() && loaders.isEmpty();
        }
    }

    /** Reads the extensions of {@code doc}; {@code typePrefix} is the service's {@code graphql-type-prefix}. */
    public static Result read(DownstreamOpenApiDocument doc, String typePrefix) {
        String prefix = typePrefix != null ? typePrefix : "";
        Map<String, Map<String, Object>> schemas = doc.componentSchemas();
        List<Reference> references = new ArrayList<>();
        List<HasMany> hasMany = new ArrayList<>();
        List<Key> keys = new ArrayList<>();
        List<Loader> loaders = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        Map<String, String> keyByType = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> schema : schemas.entrySet()) {
            Object key = schema.getValue().get(BATCH_KEY);
            if (key == null) continue;
            String where = "OpenAPI " + BATCH_KEY + " on " + schema.getKey();
            if (!(key instanceof String property) || property.isBlank()) {
                problems.add(where + ": expected a property name");
            } else if (!properties(schema.getValue()).containsKey(property.trim())) {
                problems.add(where + ": " + schema.getKey() + " has no property '" + property.trim() + "'");
            } else {
                keys.add(new Key(OpenApiToDescriptor.protoTypeName(prefix, schema.getKey()), property.trim(), where));
                keyByType.put(schema.getKey(), property.trim());
            }
        }

        for (Map.Entry<String, Map<String, Object>> schema : schemas.entrySet()) {
            String parent = OpenApiToDescriptor.protoTypeName(prefix, schema.getKey());
            for (Map.Entry<String, Object> prop : properties(schema.getValue()).entrySet()) {
                if (!(prop.getValue() instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) raw;
                String at = schema.getKey() + "." + prop.getKey();
                if (p.containsKey(REFERENCES)) {
                    String where = "OpenAPI " + REFERENCES + " on " + at;
                    Object target = p.get(REFERENCES);
                    if (!(target instanceof String name) || name.isBlank()) {
                        problems.add(where + ": expected a schema name");
                    } else if (!schemas.containsKey(name.trim())) {
                        problems.add(where + ": no schema '" + name.trim() + "' in the document");
                    } else {
                        references.add(new Reference(
                                parent,
                                prop.getKey(),
                                OpenApiToDescriptor.protoTypeName(prefix, name.trim()),
                                text(p.get(FIELD)),
                                where));
                    }
                }
                if (p.containsKey(HAS_MANY)) {
                    String where = "OpenAPI " + HAS_MANY + " on " + at;
                    if (!(p.get(HAS_MANY) instanceof Map<?, ?> many)) {
                        problems.add(where + ": expected {type, group-by, field}");
                        continue;
                    }
                    String type = text(many.get("type"));
                    String groupBy = text(many.get("group-by"));
                    if (type == null || !schemas.containsKey(type)) {
                        problems.add(where + ": " + (type == null ? "missing 'type'" : "no schema '" + type + "'"));
                    } else if (groupBy == null) {
                        problems.add(where + ": missing 'group-by'");
                    } else if (!properties(schemas.get(type)).containsKey(groupBy)) {
                        problems.add(where + ": " + type + " has no property '" + groupBy + "' to group by");
                    } else {
                        hasMany.add(new HasMany(
                                parent,
                                prop.getKey(),
                                OpenApiToDescriptor.protoTypeName(prefix, type),
                                groupBy,
                                text(many.get("field")),
                                where));
                    }
                }
            }
        }

        Set<String> loaderNames = new LinkedHashSet<>();
        for (DownstreamOpenApiDocument.Operation op : doc.operations()) {
            Object raw = op.extensions().get(LOADER);
            if (raw == null) continue;
            String where = "OpenAPI " + LOADER + " on " + op.httpMethod() + " " + op.path();
            if (!(raw instanceof Map<?, ?> m)) {
                problems.add(where + ": expected {type, ids-param, result-field, group-by, ...}");
                continue;
            }
            if (!"GET".equals(op.httpMethod())) {
                problems.add(where + ": a loader operation must be a GET");
                continue;
            }
            String type = text(m.get("type"));
            if (type == null || !schemas.containsKey(type)) {
                problems.add(where + ": " + (type == null ? "missing 'type'" : "no schema '" + type + "'"));
                continue;
            }
            RestBatchLoaderConfig cfg = new RestBatchLoaderConfig();
            cfg.setType(OpenApiToDescriptor.protoTypeName(prefix, type));
            cfg.setEndpoint(op.path());
            if (keyByType.containsKey(type)) cfg.setKeyField(keyByType.get(type));
            String idsParam = text(m.get("ids-param"));
            if (idsParam != null) cfg.setIdsParam(idsParam);
            String keyField = text(m.get("key-field"));
            if (keyField != null) cfg.setKeyField(keyField);
            cfg.setResultField(text(m.get("result-field")));
            cfg.setGroupBy(text(m.get("group-by")));
            if (m.get("batch-endpoint") instanceof Boolean b) cfg.setBatchEndpoint(b);
            if (m.get("timeout-seconds") instanceof Number n) cfg.setTimeoutSeconds(n.intValue());
            if (m.get("max-batch-size") instanceof Number n) cfg.setMaxBatchSize(n.intValue());
            if (m.get("max-concurrency") instanceof Number n) cfg.setMaxConcurrency(n.intValue());
            String name = cfg.getType() + "#" + (cfg.getGroupBy() == null ? "" : cfg.getGroupBy());
            if (!loaderNames.add(name)) {
                problems.add(where + ": a second loader for " + type
                        + (cfg.getGroupBy() == null ? "" : " grouped by " + cfg.getGroupBy()));
                continue;
            }
            loaders.add(new Loader(cfg, where));
        }
        return new Result(
                List.copyOf(references),
                List.copyOf(hasMany),
                List.copyOf(keys),
                List.copyOf(loaders),
                List.copyOf(problems));
    }

    /** The properties of a schema, including those of inline {@code allOf} parts. */
    public static Map<String, Object> properties(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        collect(schema, out);
        return out;
    }

    private static void collect(Map<String, Object> schema, Map<String, Object> out) {
        if (schema == null) return;
        if (schema.get("allOf") instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    collect(cast, out);
                }
            }
        }
        if (schema.get("properties") instanceof Map<?, ?> props) {
            for (Map.Entry<?, ?> e : props.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        }
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
