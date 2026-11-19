package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.ui.FieldRelationshipProvider;
import dev.appiaworks.shiguchi.ui.GrpcUiSchemaDto;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feeds the console's output-schema view from the {@link RelationshipResolver} the GraphQL schema is
 * built with, so the badges show the relationships the gateway actually wires, whichever source
 * declared them. Registered only when the UI module is on the classpath.
 */
@Component
@ConditionalOnClass(name = "dev.appiaworks.shiguchi.ui.FieldRelationshipProvider")
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public class ConsoleFieldRelationships implements FieldRelationshipProvider {

    private final Supplier<RelationshipResolver> resolver;

    @org.springframework.beans.factory.annotation.Autowired
    public ConsoleFieldRelationships(GraphQlSchemaBuilder schema) {
        this(schema::relationshipResolver);
    }

    ConsoleFieldRelationships(Supplier<RelationshipResolver> resolver) {
        this.resolver = resolver;
    }

    @Override
    public List<GrpcUiSchemaDto.RelationshipSchema> relationships(Descriptor parent, FieldDescriptor field) {
        return resolver.get().relationships(parent, field).stream()
                .filter(r -> r.source() != RelationshipSource.Source.SUGGESTED)
                .map(r -> new GrpcUiSchemaDto.RelationshipSchema(
                        r.fieldName(),
                        r.resolvedType(),
                        r.many(),
                        r.groupBy() == null ? "" : r.groupBy(),
                        r.source().name().toLowerCase(Locale.ROOT)))
                .toList();
    }
}
