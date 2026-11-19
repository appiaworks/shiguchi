package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.List;
import java.util.Optional;

/**
 * One place relationships and batch keys can be declared. A {@link RelationshipResolver} merges
 * its sources in order, a later source overriding an earlier one on the same relationship or type.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public interface RelationshipSource {

    /** Where a relationship or key came from. */
    enum Source {
        /** A proto field annotation ({@code resolves_type}, {@code resolves_many}, {@code batch_key}). */
        ANNOTATION,
        /** An OpenAPI {@code x-shiguchi-*} extension. */
        EXTENSION,
        /** The gateway YAML. */
        YAML,
        /** A Spring bean. */
        BEAN,
        /** A heuristic guess, only ever reported, never wired. */
        SUGGESTED
    }

    /**
     * A batch key: the field of an entity type that identifies it in a loader.
     *
     * @param typeName the entity's message name
     * @param protoField the key field's proto name
     * @param jsonName the key field's JSON name
     * @param source where the key was declared
     */
    record BatchKey(String typeName, String protoField, String jsonName, Source source) {}

    /** The kind of source, stamped on what it returns. */
    Source source();

    /** The relationships declared on {@code field} of {@code parent}, in schema order; none by default. */
    default List<GraphQlSchemaBuilder.Relationship> relationships(Descriptor parent, FieldDescriptor field) {
        return List.of();
    }

    /** The batch key of {@code entity}: its first key field in declaration order; none by default. */
    default Optional<BatchKey> batchKey(Descriptor entity) {
        return Optional.empty();
    }
}
