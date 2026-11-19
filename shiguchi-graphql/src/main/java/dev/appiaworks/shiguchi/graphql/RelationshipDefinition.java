package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import java.util.List;

/**
 * A relationship declared as a Spring bean, the code form of the YAML {@code relationships[]}
 * entry. It is a {@link RelationshipSource}, so a bean of this type is picked up with no further
 * wiring, and it sits after the YAML source: it wins on the same {@code Type.field}.
 *
 * <pre>{@code
 * @Bean RelationshipDefinition bookAuthor() {
 *     return RelationshipDefinition.one("Book.author_id", "Author");
 * }
 * @Bean RelationshipDefinition authorBooks() {
 *     return RelationshipDefinition.many("Author.id", "Book", "author_id").named("books");
 * }
 * }</pre>
 *
 * <p>The parent type and its source field must exist in a route or service of the gateway, and the
 * target must have a loader: a {@link BatchLoaderDefinition} bean, a {@code loader_for} route or a
 * REST batch loader. A reference that resolves to nothing fails startup, like a bad YAML entry.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public final class RelationshipDefinition implements RelationshipSource {

    private final String from;
    private final String to;
    private final boolean many;
    private final String groupBy;
    private final String field;

    private RelationshipDefinition(String from, String to, boolean many, String groupBy, String field) {
        if (YamlRelationshipSource.split(from) == null) {
            throw new IllegalArgumentException("from must be 'Type.field', got '" + from + "'");
        }
        if (to == null || to.isBlank()) throw new IllegalArgumentException("A relationship needs a target type");
        if (many && (groupBy == null || groupBy.isBlank())) {
            throw new IllegalArgumentException("A one-to-many relationship needs groupBy");
        }
        this.from = from.trim();
        this.to = to.trim();
        this.many = many;
        this.groupBy = groupBy;
        this.field = field;
    }

    /**
     * A to-one relationship: the id in {@code from} ({@code Type.field}) resolves to one {@code to}
     * entity, exposed as a companion field of the parent (the id's name without a trailing {@code Id}).
     */
    public static RelationshipDefinition one(String from, String to) {
        return new RelationshipDefinition(from, to, false, null, null);
    }

    /**
     * A to-many relationship: the parent's key in {@code from} resolves to the {@code to} entities
     * whose {@code groupBy} field holds it, exposed as a list field (the target's name, lower-cased
     * and pluralised, unless {@link #named} says otherwise).
     */
    public static RelationshipDefinition many(String from, String to, String groupBy) {
        return new RelationshipDefinition(from, to, true, groupBy, null);
    }

    /** The same relationship with the companion field named {@code field}. */
    public RelationshipDefinition named(String field) {
        return new RelationshipDefinition(from, to, many, groupBy, field);
    }

    @Override
    public Source source() {
        return Source.BEAN;
    }

    @Override
    public List<Relationship> relationships(Descriptor parent, FieldDescriptor source) {
        String[] parts = YamlRelationshipSource.split(from);
        if (!parts[0].equals(parent.getName())) return List.of();
        if (!parts[1].equals(source.getName()) && !parts[1].equals(source.getJsonName())) return List.of();
        Relationship declared =
                YamlRelationshipSource.toRelationship(toConfig(), parent.getName(), source.getJsonName());
        return List.of(new Relationship(
                declared.parentType(),
                declared.fieldName(),
                declared.idJsonName(),
                declared.resolvedType(),
                declared.groupBy(),
                Source.BEAN));
    }

    /** The YAML entry this definition is the code form of, for the shared validation. */
    RelationshipConfig toConfig() {
        RelationshipConfig config = new RelationshipConfig();
        config.setFrom(from);
        config.setTo(to);
        config.setMany(many);
        config.setGroupBy(groupBy);
        config.setField(field);
        return config;
    }

    @Override
    public String toString() {
        return "RelationshipDefinition[" + from + (many ? " -> many " : " -> ") + to + "]";
    }
}
