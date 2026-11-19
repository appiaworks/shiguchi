package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.ResolvesMany;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads {@code resolves_type}, {@code resolves_many} and {@code batch_key} from the proto field options. */
final class AnnotationRelationshipSource implements RelationshipSource {

    private static final Logger log = LoggerFactory.getLogger(AnnotationRelationshipSource.class);

    @Override
    public Source source() {
        return Source.ANNOTATION;
    }

    @Override
    public List<Relationship> relationships(Descriptor parent, FieldDescriptor field) {
        FieldRule ann = field.getOptions().getExtension(GraphQlProto.field);
        List<Relationship> out = new ArrayList<>();
        if (ann == null) return out;
        String typeName = parent.getName();
        if (!ann.getResolvesTypeList().isEmpty()) {
            List<String> resolvesTypes = ann.getResolvesTypeList();
            String jsonName = field.getJsonName(); // e.g. "typeId", "solarSystemId"
            if (resolvesTypes.size() == 1) {
                String resolverName = jsonName.endsWith("Id") ? jsonName.substring(0, jsonName.length() - 2) : jsonName;
                out.add(new Relationship(
                        typeName, resolverName, jsonName, resolvesTypes.get(0), "", Source.ANNOTATION));
            } else {
                String prefix = commonTypeNamePrefix(resolvesTypes);
                for (String resolvedType : resolvesTypes) {
                    String stripped = resolvedType.substring(prefix.length());
                    String resolverName = Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
                    out.add(new Relationship(typeName, resolverName, jsonName, resolvedType, "", Source.ANNOTATION));
                }
            }
        }
        if (ann.hasResolvesMany()) {
            ResolvesMany many = ann.getResolvesMany();
            if (many.getType().isEmpty() || many.getLoader().isEmpty()) {
                log.warn(
                        "resolves_many on {}.{} needs a type and a loader (the group_by of the one-to-many loader); skipped",
                        typeName,
                        field.getName());
            } else {
                String listName = many.getField().isEmpty()
                        ? Character.toLowerCase(many.getType().charAt(0))
                                + many.getType().substring(1) + "s"
                        : many.getField();
                out.add(new Relationship(
                        typeName,
                        listName,
                        field.getJsonName(),
                        many.getType(),
                        GrpcBatchDataLoaderRegistrar.snakeToCamel(many.getLoader()),
                        Source.ANNOTATION));
            }
        }
        return out;
    }

    @Override
    public Optional<BatchKey> batchKey(Descriptor entity) {
        for (FieldDescriptor field : entity.getFields()) {
            var rule = field.getOptions().getExtension(GraphQlProto.field);
            if (rule != null && rule.getBatchKey()) {
                return Optional.of(
                        new BatchKey(entity.getName(), field.getName(), field.getJsonName(), Source.ANNOTATION));
            }
        }
        return Optional.empty();
    }

    static String commonTypeNamePrefix(List<String> names) {
        if (names.isEmpty()) return "";
        String first = names.get(0);
        int len = first.length();
        for (String s : names) {
            int shared = 0;
            int max = Math.min(len, s.length());
            while (shared < max && first.charAt(shared) == s.charAt(shared)) shared++;
            len = shared;
        }
        int wordBoundary = -1;
        for (int i = 1; i < len; i++) {
            if (Character.isUpperCase(first.charAt(i))) wordBoundary = i;
        }
        return wordBoundary > 0 ? first.substring(0, wordBoundary) : first.substring(0, len);
    }
}
