package dev.appiaworks.shiguchi.ui;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.List;

/**
 * Tells the console which GraphQL relationships a field carries, whatever declared them (a proto
 * annotation, an OpenAPI extension, the YAML or a bean). The GraphQL module supplies the
 * implementation when it is present; without one the console reads {@code resolves_type} from the
 * field options and shows annotation-declared references only.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
@FunctionalInterface
public interface FieldRelationshipProvider {

    /** The relationships declared on {@code field} of {@code parent}; empty when it is not a reference. */
    List<GrpcUiSchemaDto.RelationshipSchema> relationships(Descriptor parent, FieldDescriptor field);
}
