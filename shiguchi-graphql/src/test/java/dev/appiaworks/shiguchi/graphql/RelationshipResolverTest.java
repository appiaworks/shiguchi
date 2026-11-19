package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.BatchKey;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.Source;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.ResolvesMany;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelationshipResolverTest {

    private final RelationshipResolver resolver = RelationshipResolver.annotationsOnly();

    @Test
    @DisplayName(
            "GIVEN one resolves_type on typeId WHEN resolving SHOULD name the companion field without the Id suffix")
    void given_singleResolvesType_when_resolving_should_stripIdSuffix() throws Exception {
        Descriptor msg = message(
                "Order", "customer_id", Type.TYPE_INT64, FieldRule.newBuilder().addResolvesType("Customer"));

        List<Relationship> rels = resolver.relationships(msg, msg.findFieldByName("customer_id"));

        assertThat(rels)
                .containsExactly(
                        new Relationship("Order", "customer", "customerId", "Customer", "", Source.ANNOTATION));
        assertThat(rels.get(0).source()).isEqualTo(Source.ANNOTATION);
    }

    @Test
    @DisplayName(
            "GIVEN several resolves_type WHEN resolving SHOULD name each companion by the type's shared-prefix remainder")
    void given_multiResolvesType_when_resolving_should_useCommonPrefixNaming() throws Exception {
        Descriptor msg = message(
                "Item",
                "ref_id",
                Type.TYPE_INT64,
                FieldRule.newBuilder().addResolvesType("EveType").addResolvesType("EveGroup"));

        List<Relationship> rels = resolver.relationships(msg, msg.findFieldByName("ref_id"));

        assertThat(rels).extracting(Relationship::fieldName).containsExactly("type", "group");
        assertThat(rels).extracting(Relationship::resolvedType).containsExactly("EveType", "EveGroup");
        assertThat(rels).allMatch(r -> r.idJsonName().equals("refId") && !r.many());
    }

    @Test
    @DisplayName(
            "GIVEN resolves_many WHEN resolving SHOULD yield a one-to-many relationship grouped by the camel-cased loader")
    void given_resolvesMany_when_resolving_should_yieldGroupedRelationship() throws Exception {
        Descriptor msg = message(
                "Author",
                "id",
                Type.TYPE_INT64,
                FieldRule.newBuilder()
                        .setResolvesMany(
                                ResolvesMany.newBuilder().setType("Book").setLoader("author_id")));

        List<Relationship> rels = resolver.relationships(msg, msg.findFieldByName("id"));

        assertThat(rels)
                .containsExactly(new Relationship("Author", "books", "id", "Book", "authorId", Source.ANNOTATION));
        assertThat(rels.get(0).many()).isTrue();
    }

    @Test
    @DisplayName("GIVEN resolves_many without a loader WHEN resolving SHOULD skip it")
    void given_incompleteResolvesMany_when_resolving_should_skip() throws Exception {
        Descriptor msg = message(
                "Author",
                "id",
                Type.TYPE_INT64,
                FieldRule.newBuilder().setResolvesMany(ResolvesMany.newBuilder().setType("Book")));

        assertThat(resolver.relationships(msg, msg.findFieldByName("id"))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a batch_key field WHEN resolving the key SHOULD return it with the annotation source")
    void given_batchKeyField_when_resolvingKey_should_returnAnnotationKey() throws Exception {
        Descriptor msg = message(
                "Book", "book_ref", Type.TYPE_STRING, FieldRule.newBuilder().setBatchKey(true));

        Optional<BatchKey> key = resolver.batchKey(msg);

        assertThat(key).contains(new BatchKey("Book", "book_ref", "bookRef", Source.ANNOTATION));
    }

    @Test
    @DisplayName("GIVEN no batch_key WHEN resolving the key SHOULD be empty so loaders fall back to 'id'")
    void given_noBatchKey_when_resolvingKey_should_beEmptyAndDefaultToId() throws Exception {
        Descriptor msg = message("Book", "title", Type.TYPE_STRING, FieldRule.newBuilder());

        assertThat(resolver.batchKey(msg)).isEmpty();
        assertThat(RelationshipResolver.DEFAULT_BATCH_KEY).isEqualTo("id");
    }

    @Test
    @DisplayName("GIVEN two sources WHEN both declare the same relationship SHOULD keep the later one")
    void given_overridingSource_when_resolving_should_laterSourceWin() throws Exception {
        Descriptor msg = message(
                "Order", "customer_id", Type.TYPE_INT64, FieldRule.newBuilder().addResolvesType("Customer"));
        RelationshipSource yaml = new RelationshipSource() {
            @Override
            public Source source() {
                return Source.YAML;
            }

            @Override
            public List<Relationship> relationships(
                    Descriptor parent, com.google.protobuf.Descriptors.FieldDescriptor field) {
                return List.of(new Relationship("Order", "customer", "customerId", "Client", "", Source.YAML));
            }
        };
        var merged = new RelationshipResolver(List.of(new AnnotationRelationshipSource(), yaml));

        List<Relationship> rels = merged.relationships(msg, msg.findFieldByName("customer_id"));

        assertThat(rels).hasSize(1);
        assertThat(rels.get(0).resolvedType()).isEqualTo("Client");
        assertThat(rels.get(0).source()).isEqualTo(Source.YAML);
    }

    private static Descriptor message(String name, String fieldName, Type type, FieldRule.Builder rule)
            throws Exception {
        FieldOptions options = FieldOptions.newBuilder()
                .setExtension(GraphQlProto.field, rule.build())
                .build();
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName(name.toLowerCase() + "_resolver.proto")
                .setSyntax("proto3")
                .addDependency("shiguchi/graphql.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName(name)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName(fieldName)
                                .setNumber(1)
                                .setType(type)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setOptions(options)))
                .build();
        return FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()})
                .findMessageTypeByName(name);
    }
}
