package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.routing.DownstreamOpenApiDocument;
import dev.appiaworks.shiguchi.routing.OpenApiRelationshipExtensions;
import dev.appiaworks.shiguchi.routing.OpenApiToDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Proposes relationships for a REST_DOWNSTREAM OpenAPI document, from names alone. It is a helper
 * for whoever writes the declarations: it <b>never wires anything</b>. Its output is not a
 * {@link RelationshipSource}, nothing registers it, and the {@link RelationshipResolver} never sees it,
 * so a suggestion becomes a relationship only when someone copies it into
 * {@code x-shiguchi-references} / {@code x-shiguchi-has-many} or the YAML.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>A property named {@code <type>Id} or {@code <type>_id} whose stem equals another schema's name
 *       (ignoring case and separators) is suggested as a many-to-one to that schema.
 *   <li>The inverse is suggested as the plural collection: {@code Customer.orders}, grouped by that
 *       property, when the target schema has an {@code id} property.
 *   <li>A property that already carries {@code x-shiguchi-references} or {@code x-shiguchi-has-many}
 *       is left alone.
 * </ul>
 *
 * Every candidate has {@link RelationshipSource.Source#SUGGESTED}. A name that does not follow the
 * pattern (an {@code ownerId} pointing at a {@code User}) is not guessed at: the suggester returns
 * nothing for it rather than something wrong.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public final class RelationshipSuggester {

    private RelationshipSuggester() {}

    /** Candidates for {@code doc}, with the synthetic type names of {@code typePrefix}. */
    public static List<Relationship> suggest(DownstreamOpenApiDocument doc, String typePrefix) {
        return suggest(doc.componentSchemas(), typePrefix);
    }

    /** Candidates for the {@code components.schemas} map {@code schemas}. */
    public static List<Relationship> suggest(Map<String, Map<String, Object>> schemas, String typePrefix) {
        String prefix = typePrefix != null ? typePrefix : "";
        List<Relationship> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> schema : schemas.entrySet()) {
            for (Map.Entry<String, Object> prop :
                    OpenApiRelationshipExtensions.properties(schema.getValue()).entrySet()) {
                String stem = stem(prop.getKey());
                if (stem == null) continue;
                if (prop.getValue() instanceof Map<?, ?> p
                        && (p.containsKey(OpenApiRelationshipExtensions.REFERENCES)
                                || p.containsKey(OpenApiRelationshipExtensions.HAS_MANY))) {
                    continue;
                }
                String target = match(schemas, schema.getKey(), stem);
                if (target == null) continue;
                String parentType = OpenApiToDescriptor.protoTypeName(prefix, schema.getKey());
                String targetType = OpenApiToDescriptor.protoTypeName(prefix, target);
                String idJson = GrpcBatchDataLoaderRegistrar.snakeToCamel(prop.getKey());
                out.add(new Relationship(
                        parentType,
                        YamlRelationshipSource.defaultName(idJson),
                        idJson,
                        targetType,
                        "",
                        RelationshipSource.Source.SUGGESTED));
                if (OpenApiRelationshipExtensions.properties(schemas.get(target))
                        .containsKey("id")) {
                    String child = schema.getKey();
                    String collection = Character.toLowerCase(child.charAt(0)) + child.substring(1) + "s";
                    out.add(new Relationship(
                            targetType, collection, "id", parentType, idJson, RelationshipSource.Source.SUGGESTED));
                }
            }
        }
        return out;
    }

    /** The lower-cased stem of {@code fooId} / {@code foo_id}, or null for any other name. */
    private static String stem(String property) {
        String stem;
        if (property.endsWith("_id")) stem = property.substring(0, property.length() - 3);
        else if (property.endsWith("Id")) stem = property.substring(0, property.length() - 2);
        else return null;
        stem = normalise(stem);
        return stem.isEmpty() ? null : stem;
    }

    private static String match(Map<String, Map<String, Object>> schemas, String self, String stem) {
        for (String name : schemas.keySet()) {
            if (!name.equals(self) && normalise(name).equals(stem)) return name;
        }
        return null;
    }

    private static String normalise(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
