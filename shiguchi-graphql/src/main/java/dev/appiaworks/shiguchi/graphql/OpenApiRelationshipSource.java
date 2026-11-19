package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.routing.OpenApiRelationshipExtensions;
import dev.appiaworks.shiguchi.routing.OpenApiToDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code x-shiguchi-references}, {@code x-shiguchi-has-many} and {@code x-shiguchi-batch-key}
 * extensions of the REST_DOWNSTREAM documents (see {@link OpenApiRelationshipExtensions}). It sits above
 * the proto annotations and below the YAML, so {@code shiguchi.services[].relationships} still wins on
 * the same field.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
final class OpenApiRelationshipSource implements RelationshipSource {

    private final OpenApiRelationshipIndex index;

    OpenApiRelationshipSource(OpenApiRelationshipIndex index) {
        this.index = index;
    }

    @Override
    public Source source() {
        return Source.EXTENSION;
    }

    @Override
    public List<Relationship> relationships(Descriptor parent, FieldDescriptor field) {
        List<Relationship> out = new ArrayList<>();
        for (OpenApiRelationshipIndex.Entry entry : index.entries()) {
            if (!owns(entry, parent)) continue;
            for (OpenApiRelationshipExtensions.Reference ref :
                    entry.extensions().references()) {
                if (!ref.parentType().equals(parent.getName()) || !isField(field, ref.property())) continue;
                out.add(stamp(YamlRelationshipSource.toRelationship(
                        config(parent, field, ref.targetType(), ref.field(), null),
                        parent.getName(),
                        field.getJsonName())));
            }
            for (OpenApiRelationshipExtensions.HasMany many : entry.extensions().hasMany()) {
                if (!many.parentType().equals(parent.getName()) || !isField(field, many.property())) continue;
                out.add(stamp(YamlRelationshipSource.toRelationship(
                        config(parent, field, many.targetType(), many.field(), many.groupBy()),
                        parent.getName(),
                        field.getJsonName())));
            }
        }
        return out;
    }

    @Override
    public Optional<BatchKey> batchKey(Descriptor entity) {
        for (OpenApiRelationshipIndex.Entry entry : index.entries()) {
            if (!owns(entry, entity)) continue;
            for (OpenApiRelationshipExtensions.Key key : entry.extensions().keys()) {
                if (!key.type().equals(entity.getName())) continue;
                for (FieldDescriptor f : entity.getFields()) {
                    if (isField(f, key.property())) {
                        return Optional.of(
                                new BatchKey(entity.getName(), f.getName(), f.getJsonName(), Source.EXTENSION));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The config form of an extension, so the extension and the YAML share one translation. */
    static RelationshipConfig config(Descriptor parent, FieldDescriptor field, String to, String name, String groupBy) {
        RelationshipConfig rel = new RelationshipConfig();
        rel.setFrom(parent.getName() + "." + field.getName());
        rel.setTo(to);
        rel.setField(name);
        if (groupBy != null) {
            rel.setMany(true);
            rel.setGroupBy(groupBy);
        }
        return rel;
    }

    private static Relationship stamp(Relationship r) {
        return new Relationship(
                r.parentType(), r.fieldName(), r.idJsonName(), r.resolvedType(), r.groupBy(), Source.EXTENSION);
    }

    private static boolean owns(OpenApiRelationshipIndex.Entry entry, Descriptor type) {
        return entry.context().getFileDescriptors().containsKey(type.getFile().getName());
    }

    private static boolean isField(FieldDescriptor field, String property) {
        return field.getName().equals(OpenApiToDescriptor.protoFieldName(property));
    }
}
