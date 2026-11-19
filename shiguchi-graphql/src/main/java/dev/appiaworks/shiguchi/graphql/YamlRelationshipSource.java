package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code shiguchi.services[].relationships} and {@code batch-keys}: the YAML form of the
 * {@code resolves_type}, {@code resolves_many} and {@code batch_key} annotations, for teams that
 * cannot edit the protos. It sits after the annotation source, so it wins on the same field.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public final class YamlRelationshipSource implements RelationshipSource {

    private final GatewayProperties properties;

    /** A source over the services of {@code properties}, read on every call so a reload is seen. */
    public YamlRelationshipSource(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Source source() {
        return Source.YAML;
    }

    @Override
    public List<Relationship> relationships(Descriptor parent, FieldDescriptor field) {
        List<Relationship> out = new ArrayList<>();
        for (ServiceDefinition service : services()) {
            for (RelationshipConfig rel : service.getRelationships()) {
                if (!matches(rel.getFrom(), parent.getName(), field.getName())) continue;
                if (blank(rel.getTo())) continue; // reported by the registrar's validation
                out.add(toRelationship(rel, parent.getName(), field.getJsonName()));
            }
        }
        return out;
    }

    @Override
    public Optional<BatchKey> batchKey(Descriptor entity) {
        for (ServiceDefinition service : services()) {
            String name = service.getBatchKeys().get(entity.getName());
            if (blank(name)) continue;
            for (FieldDescriptor field : entity.getFields()) {
                if (field.getName().equals(name) || field.getJsonName().equals(name)) {
                    return Optional.of(
                            new BatchKey(entity.getName(), field.getName(), field.getJsonName(), Source.YAML));
                }
            }
        }
        return Optional.empty();
    }

    /** The relationship a config entry declares, given the parent's JSON name for the source field. */
    static Relationship toRelationship(RelationshipConfig rel, String parentType, String idJsonName) {
        if (rel.isMany()) {
            String listName = blank(rel.getField())
                    ? Character.toLowerCase(rel.getTo().charAt(0)) + rel.getTo().substring(1) + "s"
                    : rel.getField();
            String groupBy = blank(rel.getGroupBy()) ? "" : GrpcBatchDataLoaderRegistrar.snakeToCamel(rel.getGroupBy());
            return new Relationship(parentType, listName, idJsonName, rel.getTo(), groupBy, Source.YAML);
        }
        String name = blank(rel.getField()) ? defaultName(idJsonName) : rel.getField();
        return new Relationship(parentType, name, idJsonName, rel.getTo(), "", Source.YAML);
    }

    /** The companion field name when none is given: the id's JSON name without a trailing {@code Id}. */
    static String defaultName(String jsonName) {
        return jsonName.endsWith("Id") && jsonName.length() > 2
                ? jsonName.substring(0, jsonName.length() - 2)
                : jsonName;
    }

    /** True when {@code from} is {@code type.field}. */
    static boolean matches(String from, String type, String field) {
        String[] parts = split(from);
        return parts != null && parts[0].equals(type) && parts[1].equals(field);
    }

    /** {@code [Type, field]} of a {@code Type.field} reference, or null when malformed. */
    static String[] split(String from) {
        if (blank(from)) return null;
        int dot = from.indexOf('.');
        if (dot <= 0 || dot == from.length() - 1) return null;
        return new String[] {
            from.substring(0, dot).trim(), from.substring(dot + 1).trim()
        };
    }

    private List<ServiceDefinition> services() {
        return properties == null || properties.getServices() == null ? List.of() : properties.getServices();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Batch keys of all services, for the validation. */
    Map<String, String> allBatchKeys() {
        Map<String, String> all = new java.util.LinkedHashMap<>();
        for (ServiceDefinition s : services()) all.putAll(s.getBatchKeys());
        return all;
    }
}
