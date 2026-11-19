package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.ui.GrpcUiSchemaDto.RelationshipSchema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConsoleFieldRelationships")
class ConsoleFieldRelationshipsTest {

    @Test
    @DisplayName("GIVEN a YAML one-to-many relationship WHEN asked for the field SHOULD report many, group-by and yaml")
    void GIVEN_yamlOneToMany_WHEN_asked_SHOULD_reportManyGroupByAndSource() throws Exception {
        Descriptor author = message("Author", "id", FieldRule.newBuilder());
        var provider = new ConsoleFieldRelationships(
                () -> RelationshipResolver.of(yaml("Author.id", "Book", true), List.of()));

        List<RelationshipSchema> out = provider.relationships(author, author.findFieldByName("id"));

        assertThat(out).containsExactly(new RelationshipSchema("books", "Book", true, "authorId", "yaml"));
    }

    @Test
    @DisplayName(
            "GIVEN an annotation relationship WHEN asked for the field SHOULD report a single reference from annotation")
    void GIVEN_annotation_WHEN_asked_SHOULD_reportSingleReference() throws Exception {
        Descriptor order =
                message("Order", "customer_id", FieldRule.newBuilder().addResolvesType("Customer"));
        var provider = new ConsoleFieldRelationships(() -> RelationshipResolver.of(new GatewayProperties(), List.of()));

        List<RelationshipSchema> out = provider.relationships(order, order.findFieldByName("customer_id"));

        assertThat(out).containsExactly(new RelationshipSchema("customer", "Customer", false, "", "annotation"));
    }

    @Test
    @DisplayName("GIVEN a YAML target overriding an annotation WHEN asked SHOULD report the winning yaml source only")
    void GIVEN_yamlOverridesAnnotation_WHEN_asked_SHOULD_reportYamlOnly() throws Exception {
        Descriptor order =
                message("Order", "customer_id", FieldRule.newBuilder().addResolvesType("Customer"));
        var provider = new ConsoleFieldRelationships(
                () -> RelationshipResolver.of(yaml("Order.customer_id", "Client", false, "customer"), List.of()));

        List<RelationshipSchema> out = provider.relationships(order, order.findFieldByName("customer_id"));

        assertThat(out).containsExactly(new RelationshipSchema("customer", "Client", false, "", "yaml"));
    }

    private static GatewayProperties yaml(String from, String to, boolean many) {
        return yaml(from, to, many, null);
    }

    private static GatewayProperties yaml(String from, String to, boolean many, String field) {
        RelationshipConfig r = new RelationshipConfig();
        r.setFrom(from);
        r.setTo(to);
        r.setField(field);
        r.setMany(many);
        if (many) r.setGroupBy("author_id");
        ServiceDefinition service = new ServiceDefinition();
        service.getRelationships().add(r);
        GatewayProperties properties = new GatewayProperties();
        properties.setServices(new ArrayList<>(List.of(service)));
        return properties;
    }

    private static Descriptor message(String name, String fieldName, FieldRule.Builder rule) throws Exception {
        FieldOptions options = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.field, rule.build())
                .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName(name.toLowerCase() + "_console.proto")
                .setSyntax("proto3")
                .addDependency("shiguchi/graphql.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(name)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(Type.TYPE_INT64)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setOptions(options)))
                .build();
        return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()})
                .findMessageTypeByName(name);
    }
}
