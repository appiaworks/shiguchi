package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.routing.DownstreamOpenApiDocument;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * What the gateway resolved for GraphQL relationships, as plain maps for the {@code gateway-relationships}
 * actuator endpoint: the effective relationships per parent type with their source and loader, the
 * batch keys, the loaders, and suggestions.
 *
 * <p>The suggestions are heuristic candidates over the REST_DOWNSTREAM documents
 * ({@link RelationshipSuggester}). They are labelled {@code suggested} and {@code wired: false}: nothing
 * in the schema or the loaders follows them until someone declares the relationship.
 *
 * <p>Read-only and stateless: every call reads the current schema, loaders and documents.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public class RelationshipReport {

    private static final Logger log = LoggerFactory.getLogger(RelationshipReport.class);

    /** One REST_DOWNSTREAM document and the type prefix of its service, for the suggestions. */
    record RestDocument(String service, String typePrefix, DownstreamOpenApiDocument document) {}

    private final Supplier<List<Relationship>> relationships;
    private final Supplier<List<RelationshipSource.BatchKey>> batchKeys;
    private final Supplier<Map<String, String>> loaderKinds;
    private final Supplier<Map<String, String>> groupedLoaderKinds;
    private final Supplier<List<RestDocument>> documents;

    @Autowired
    public RelationshipReport(GraphQlSchemaBuilder schema, BatchLoaderPipeline pipeline, GrpcRouteRegistry registry) {
        this(
                schema::getRelationships,
                schema::getBatchKeys,
                pipeline::loaderKinds,
                pipeline::groupedLoaderKinds,
                () -> restDocuments(registry));
    }

    RelationshipReport(
            Supplier<List<Relationship>> relationships,
            Supplier<List<RelationshipSource.BatchKey>> batchKeys,
            Supplier<Map<String, String>> loaderKinds,
            Supplier<Map<String, String>> groupedLoaderKinds,
            Supplier<List<RestDocument>> documents) {
        this.relationships = relationships;
        this.batchKeys = batchKeys;
        this.loaderKinds = loaderKinds;
        this.groupedLoaderKinds = groupedLoaderKinds;
        this.documents = documents;
    }

    /**
     * The report.
     *
     * @param type when not null or blank, only the relationships and batch keys of this parent type, and the
     *     suggestions for it
     */
    public Map<String, Object> report(String type) {
        boolean all = type == null || type.isBlank();
        Map<String, String> byId = loaderKinds.get();
        Map<String, String> grouped = groupedLoaderKinds.get();
        List<Relationship> effective = relationships.get();

        Map<String, List<Map<String, Object>>> byParent = new TreeMap<>();
        for (Relationship r : effective) {
            if (!all && !type.equals(r.parentType())) continue;
            byParent.computeIfAbsent(r.parentType(), k -> new ArrayList<>()).add(relationship(r, byId, grouped));
        }
        List<Map<String, Object>> types = new ArrayList<>();
        byParent.forEach((parent, list) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", parent);
            entry.put("relationships", list);
            types.add(entry);
        });

        List<Map<String, Object>> keys = new ArrayList<>();
        for (RelationshipSource.BatchKey key : batchKeys.get()) {
            if (!all && !type.equals(key.typeName())) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", key.typeName());
            entry.put("field", key.jsonName());
            entry.put("source", label(key.source()));
            keys.add(entry);
        }
        keys.sort(Comparator.comparing(e -> (String) e.get("type")));

        List<Map<String, Object>> loaders = new ArrayList<>();
        byId.forEach((name, kind) -> loaders.add(loader(name, kind, false)));
        grouped.forEach((name, kind) -> loaders.add(loader(name, kind, true)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("types", types);
        body.put("batchKeys", keys);
        body.put("loaders", loaders);
        body.put("suggestions", suggestions(effective, all ? null : type));
        return body;
    }

    private static Map<String, Object> relationship(
            Relationship r, Map<String, String> byId, Map<String, String> grouped) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", r.fieldName());
        out.put("target", r.resolvedType());
        out.put("many", r.many());
        out.put("groupBy", r.many() ? r.groupBy() : null);
        out.put("source", label(r.source()));
        String name = r.loaderName();
        String kind = (r.many() ? grouped : byId).get(name);
        out.put("loaderRegistered", kind != null);
        out.put("loader", kind == null ? null : loader(name, kind, r.many()));
        return out;
    }

    private static Map<String, Object> loader(String name, String kind, boolean grouped) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("kind", kind);
        out.put("grouped", grouped);
        return out;
    }

    private List<Map<String, Object>> suggestions(List<Relationship> effective, String type) {
        Set<String> taken = new HashSet<>();
        for (Relationship r : effective) taken.add(r.parentType() + "." + r.fieldName());
        List<Map<String, Object>> out = new ArrayList<>();
        for (RestDocument doc : documents.get()) {
            List<Relationship> candidates;
            try {
                candidates = RelationshipSuggester.suggest(doc.document(), doc.typePrefix());
            } catch (RuntimeException e) {
                log.warn(
                        "[RelationshipReport] Cannot suggest relationships for '{}': {}",
                        doc.service(),
                        e.getMessage());
                continue;
            }
            for (Relationship c : candidates) {
                if (type != null && !type.equals(c.parentType())) continue;
                if (taken.contains(c.parentType() + "." + c.fieldName())) continue;
                out.add(suggestion(doc, c));
            }
        }
        return out;
    }

    private static Map<String, Object> suggestion(RestDocument doc, Relationship c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", doc.service());
        out.put("parentType", c.parentType());
        out.put("field", c.fieldName());
        out.put("targetType", c.resolvedType());
        out.put("many", c.many());
        out.put("groupBy", c.many() ? c.groupBy() : null);
        out.put("source", label(RelationshipSource.Source.SUGGESTED));
        out.put("wired", false);
        out.put("hint", hint(doc.typePrefix(), c));
        return out;
    }

    /** The declaration that would wire the candidate, as an OpenAPI extension and as a YAML snippet. */
    private static Map<String, Object> hint(String prefix, Relationship c) {
        String target = unprefixed(prefix, c.resolvedType());
        String from = c.parentType() + "." + c.idJsonName();
        String extension;
        String yaml;
        if (c.many()) {
            extension = "x-shiguchi-has-many: {type: " + target + ", group-by: " + c.groupBy() + ", field: "
                    + c.fieldName() + "} on " + unprefixed(prefix, c.parentType()) + "." + c.idJsonName();
            yaml = "relationships: [{from: " + from + ", to: " + c.resolvedType() + ", many: true, field: "
                    + c.fieldName() + ", group-by: " + c.groupBy() + "}]";
        } else {
            extension = "x-shiguchi-references: " + target + " on " + unprefixed(prefix, c.parentType()) + "."
                    + c.idJsonName();
            yaml = "relationships: [{from: " + from + ", to: " + c.resolvedType() + "}]";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("extension", extension);
        out.put("yaml", yaml);
        return out;
    }

    private static String unprefixed(String prefix, String type) {
        return prefix != null && !prefix.isEmpty() && type.startsWith(prefix) ? type.substring(prefix.length()) : type;
    }

    private static String label(RelationshipSource.Source source) {
        return source.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<RestDocument> restDocuments(GrpcRouteRegistry registry) {
        List<RestDocument> out = new ArrayList<>();
        for (ServiceEndpointContext ctx : registry.contextsInStableOrder()) {
            if (!ctx.isRestDownstream() || ctx.getRawDownstreamSpec() == null) continue;
            String name = ctx.getConfig().getName();
            try {
                out.add(new RestDocument(
                        name,
                        ctx.getConfig().getGraphqlTypePrefix(),
                        DownstreamOpenApiDocument.parse(ctx.getRawDownstreamSpec(), ctx.getDownstreamSpecMediaType())));
            } catch (RuntimeException e) {
                log.warn("[RelationshipReport] Cannot read the OpenAPI document of '{}': {}", name, e.getMessage());
            }
        }
        return out;
    }
}
