package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.BatchKey;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.Source;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("YamlRelationshipSource")
class YamlRelationshipSourceTest {

    private final GatewayProperties properties = new GatewayProperties();
    private final ServiceDefinition service = new ServiceDefinition();

    private Descriptor type(String name, String... fields) throws Exception {
        DescriptorProto.Builder msg = DescriptorProto.newBuilder().setName(name);
        for (int i = 0; i < fields.length; i++) {
            msg.addField(FieldDescriptorProto.newBuilder()
                    .setName(fields[i])
                    .setNumber(i + 1)
                    .setType(Type.TYPE_INT64)
                    .setLabel(Label.LABEL_OPTIONAL));
        }
        FileDescriptor fd = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName(name + ".proto")
                        .setSyntax("proto3")
                        .addMessageType(msg)
                        .build(),
                new FileDescriptor[0]);
        return fd.findMessageTypeByName(name);
    }

    private YamlRelationshipSource source() {
        properties.setServices(new ArrayList<>(List.of(service)));
        return new YamlRelationshipSource(properties);
    }

    private static RelationshipConfig rel(String from, String to, String field, boolean many, String groupBy) {
        RelationshipConfig r = new RelationshipConfig();
        r.setFrom(from);
        r.setTo(to);
        r.setField(field);
        r.setMany(many);
        r.setGroupBy(groupBy);
        return r;
    }

    @Test
    @DisplayName("GIVEN a one-to-one relationship WHEN resolving SHOULD strip the Id suffix for the companion")
    void GIVEN_oneToOne_WHEN_resolving_SHOULD_stripIdSuffix() throws Exception {
        service.getRelationships().add(rel("Order.customer_id", "Customer", null, false, null));
        Descriptor order = type("Order", "customer_id");

        List<Relationship> rels = source().relationships(order, order.findFieldByName("customer_id"));

        assertThat(rels)
                .containsExactly(new Relationship("Order", "customer", "customerId", "Customer", "", Source.YAML));
    }

    @Test
    @DisplayName("GIVEN a one-to-many relationship WHEN resolving SHOULD pluralise and camel-case the group-by")
    void GIVEN_oneToMany_WHEN_resolving_SHOULD_defaultPluralName() throws Exception {
        service.getRelationships().add(rel("Author.id", "Book", null, true, "author_id"));
        Descriptor author = type("Author", "id");

        List<Relationship> rels = source().relationships(author, author.findFieldByName("id"));

        assertThat(rels).containsExactly(new Relationship("Author", "books", "id", "Book", "authorId", Source.YAML));
    }

    @Test
    @DisplayName("GIVEN an explicit field name WHEN resolving SHOULD use it as the companion")
    void GIVEN_explicitField_WHEN_resolving_SHOULD_useIt() throws Exception {
        service.getRelationships().add(rel("Author.id", "Book", "works", true, "author_id"));
        Descriptor author = type("Author", "id");

        assertThat(source().relationships(author, author.findFieldByName("id")))
                .extracting(Relationship::fieldName)
                .containsExactly("works");
    }

    @Test
    @DisplayName("GIVEN another type or field WHEN resolving SHOULD yield nothing")
    void GIVEN_noMatch_WHEN_resolving_SHOULD_beEmpty() throws Exception {
        service.getRelationships().add(rel("Author.id", "Book", null, true, "author_id"));
        YamlRelationshipSource source = source();
        Descriptor other = type("Other", "id", "x");
        Descriptor author = type("Author", "id", "x");

        assertThat(source.relationships(other, other.findFieldByName("id"))).isEmpty();
        assertThat(source.relationships(author, author.findFieldByName("x"))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a batch-keys entry WHEN resolving the key SHOULD return the field with the YAML source")
    void GIVEN_batchKey_WHEN_resolving_SHOULD_returnYamlKey() throws Exception {
        service.getBatchKeys().put("Book", "book_ref");
        Descriptor book = type("Book", "id", "book_ref");
        YamlRelationshipSource source = source();

        assertThat(source.batchKey(book)).contains(new BatchKey("Book", "book_ref", "bookRef", Source.YAML));
        assertThat(source.batchKey(type("Other", "id"))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN YAML and a bean source WHEN resolving SHOULD let the bean win and YAML stand alone otherwise")
    void GIVEN_yamlAndBean_WHEN_resolving_SHOULD_orderBySource() throws Exception {
        service.getRelationships().add(rel("Author.id", "Book", "books", true, "author_id"));
        properties.setServices(new ArrayList<>(List.of(service)));
        RelationshipSource bean = new RelationshipSource() {
            @Override
            public Source source() {
                return Source.BEAN;
            }

            @Override
            public List<Relationship> relationships(Descriptor parent, FieldDescriptor field) {
                return List.of(new Relationship("Author", "books", "id", "Tome", "", Source.BEAN));
            }
        };
        Descriptor author = type("Author", "id");
        FieldDescriptor id = author.findFieldByName("id");

        assertThat(RelationshipResolver.of(properties, List.of(bean)).relationships(author, id))
                .extracting(Relationship::resolvedType)
                .containsExactly("Tome");
        assertThat(RelationshipResolver.of(properties, List.of()).relationships(author, id))
                .extracting(Relationship::resolvedType)
                .containsExactly("Book");
    }
}
