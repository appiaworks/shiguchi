package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.BatchKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place relationships and batch keys are decided. It merges ordered
 * {@link RelationshipSource}s, lowest precedence first: a later source overrides an earlier one
 * on the same {@code (parent type, field name)} relationship or the same entity type's key.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public final class RelationshipResolver {

    /** The key field name a loader falls back to when its entity type declares none. */
    public static final String DEFAULT_BATCH_KEY = "id";

    private final List<RelationshipSource> sources;

    /** A resolver over {@code sources}, lowest precedence first. */
    public RelationshipResolver(List<RelationshipSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /** A resolver that reads the proto annotations only. */
    public static RelationshipResolver annotationsOnly() {
        return new RelationshipResolver(List.of(new AnnotationRelationshipSource()));
    }

    /**
     * The annotations, then the YAML of {@code properties}, then {@code extras} (OpenAPI extensions,
     * beans), ordered by {@link RelationshipSource.Source} so a later source wins. {@code extras}
     * that are the YAML or annotation kind slot in after the built-in one of their kind.
     */
    public static RelationshipResolver of(
            dev.appiaworks.shiguchi.config.GatewayProperties properties, List<RelationshipSource> extras) {
        List<RelationshipSource> all = new ArrayList<>();
        all.add(new AnnotationRelationshipSource());
        all.add(new YamlRelationshipSource(properties));
        if (extras != null) all.addAll(extras);
        // stable sort: built-ins first inside their precedence tier
        all.sort(java.util.Comparator.comparing(RelationshipSource::source));
        return new RelationshipResolver(all);
    }

    /** The relationships declared on {@code field} of {@code parent}, merged over all sources, each carrying its source. */
    public List<Relationship> relationships(Descriptor parent, FieldDescriptor field) {
        Map<String, Relationship> merged = new LinkedHashMap<>();
        for (RelationshipSource source : sources) {
            for (Relationship r : source.relationships(parent, field)) {
                merged.put(r.parentType() + "." + r.fieldName(), r);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** The batch key of {@code entity}: the highest-precedence source that declares one wins. */
    public Optional<BatchKey> batchKey(Descriptor entity) {
        Optional<BatchKey> found = Optional.empty();
        for (RelationshipSource source : sources) {
            Optional<BatchKey> key = source.batchKey(entity);
            if (key.isPresent()) found = key;
        }
        return found;
    }
}
