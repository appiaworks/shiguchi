package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.graphql.RelationshipSource.Source;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** BDD-style unit tests for the bean SPI: {@link BatchLoaderDefinition} and {@link RelationshipDefinition}. */
@DisplayName("Bean loaders and relationships")
class BatchLoaderDefinitionTest {

    /** Echoes every key as an entity and records each call. */
    private static class Recording implements BatchLoader {
        final List<List<String>> calls = new CopyOnWriteArrayList<>();
        final String tag;

        Recording(String tag) {
            this.tag = tag;
        }

        @Override
        public Mono<Map<String, Map<String, Object>>> loadMapped(List<String> keys, Metadata metadata) {
            calls.add(List.copyOf(keys));
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (String key : keys) result.put(key, Map.of("id", key, "from", tag));
            return Mono.just(result);
        }
    }

    private static BatchLoaderPipeline pipeline(
            Map<String, BatchLoader> serviceLoaders, DataLoaderConfig config, BatchLoaderDefinition... beans) {
        GrpcBatchDataLoaderRegistrar registrar = mock(GrpcBatchDataLoaderRegistrar.class);
        when(registrar.getLoaders()).thenReturn(serviceLoaders);
        return new BatchLoaderPipeline(registrar, (RestBatchDataLoaderRegistrar) null, config, List.of(beans));
    }

    private static List<String> keys(int count) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) keys.add("k" + i);
        return keys;
    }

    @Test
    @DisplayName(
            "GIVEN a by-id bean for a type no service serves WHEN loaded SHOULD answer from the bean and list the type")
    void GIVEN_beanOnlyType_WHEN_loaded_SHOULD_answerFromBean() {
        Recording bean = new Recording("bean");
        BatchLoaderPipeline pipeline =
                pipeline(Map.of(), new DataLoaderConfig(), BatchLoaderDefinition.byId("Author", bean));

        Map<String, Map<String, Object>> result =
                pipeline.load("Author", List.of("a", "b", "a"), new Metadata()).block();

        assertThat(pipeline.types()).containsExactly("Author");
        assertThat(pipeline.has("Author")).isTrue();
        assertThat(bean.calls).containsExactly(List.of("a", "b"));
        assertThat(result).containsOnlyKeys("a", "b");
    }

    @Test
    @DisplayName("GIVEN max-batch-size 2 WHEN a bean loader gets five keys SHOULD chunk it like any other loader")
    void GIVEN_maxBatchSize_WHEN_beanLoaded_SHOULD_chunk() {
        Recording bean = new Recording("bean");
        DataLoaderConfig config = new DataLoaderConfig();
        config.setMaxBatchSize(2);
        BatchLoaderPipeline pipeline = pipeline(Map.of(), config, BatchLoaderDefinition.byId("Author", bean));

        pipeline.load("Author", keys(5), new Metadata()).block();

        assertThat(bean.calls).extracting(List::size).containsExactlyInAnyOrder(2, 2, 1);
    }

    @Test
    @DisplayName("GIVEN a bean and a service loader for one type WHEN loaded SHOULD use the bean")
    void GIVEN_beanAndServiceLoader_WHEN_loaded_SHOULD_beanWins() {
        Recording service = new Recording("service");
        Recording bean = new Recording("bean");
        BatchLoaderPipeline pipeline =
                pipeline(Map.of("Book", service), new DataLoaderConfig(), BatchLoaderDefinition.byId("Book", bean));

        Map<String, Map<String, Object>> result =
                pipeline.load("Book", List.of("x"), new Metadata()).block();

        assertThat(result.get("x")).containsEntry("from", "bean");
        assertThat(service.calls).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN the type is pinned to an endpoint WHEN a bean and a service loader exist SHOULD use the service")
    void GIVEN_pinnedType_WHEN_loaded_SHOULD_serviceWins() {
        Recording service = new Recording("service");
        Recording bean = new Recording("bean");
        DataLoaderConfig config = new DataLoaderConfig();
        DataLoaderConfig.TypeConfig pin = new DataLoaderConfig.TypeConfig();
        pin.setEndpoint("library");
        config.getTypes().put("Book", pin);
        BatchLoaderPipeline pipeline =
                pipeline(Map.of("Book", service), config, BatchLoaderDefinition.byId("Book", bean));

        Map<String, Map<String, Object>> result =
                pipeline.load("Book", List.of("x"), new Metadata()).block();

        assertThat(result.get("x")).containsEntry("from", "service");
        assertThat(bean.calls).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a grouped bean WHEN loaded SHOULD be named Type#by:field, stay out of types() and group the children")
    void GIVEN_groupedBean_WHEN_loaded_SHOULD_groupChildrenByParent() {
        List<List<String>> calls = new CopyOnWriteArrayList<>();
        BatchLoaderDefinition grouped = BatchLoaderDefinition.grouped("Book", "author_id", (keys, md) -> {
            calls.add(List.copyOf(keys));
            return Mono.just(List.<Map<String, Object>>of(
                    Map.of("id", "b1", "authorId", "a1"),
                    Map.of("id", "b2", "authorId", "a1"),
                    Map.of("id", "b3", "authorId", "a2")));
        });
        BatchLoaderPipeline pipeline = pipeline(Map.of(), new DataLoaderConfig(), grouped);

        GroupedOutcome outcome = pipeline.loadGrouped("Book#by:authorId", List.of("a1", "a2", "a3"), new Metadata())
                .block();

        assertThat(grouped.name()).isEqualTo("Book#by:authorId");
        assertThat(pipeline.groupedNames()).containsExactly("Book#by:authorId");
        assertThat(pipeline.types()).isEmpty();
        assertThat(calls).containsExactly(List.of("a1", "a2", "a3"));
        assertThat(outcome.groups().get("a1")).hasSize(2);
        assertThat(outcome.groups().get("a2")).hasSize(1);
        assertThat(outcome.groups().get("a3")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN two beans for the same loader WHEN the pipeline is built SHOULD fail naming the loader")
    void GIVEN_duplicateBeans_WHEN_built_SHOULD_fail() {
        BatchLoaderDefinition one = BatchLoaderDefinition.byId("Author", new Recording("one"));
        BatchLoaderDefinition two = BatchLoaderDefinition.byId("Author", new Recording("two"));

        assertThatThrownBy(() -> pipeline(Map.of(), new DataLoaderConfig(), one, two))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Author");
    }

    @Test
    @DisplayName("GIVEN a blank type or group field WHEN a definition is built SHOULD be rejected")
    void GIVEN_blankArguments_WHEN_built_SHOULD_beRejected() {
        assertThatThrownBy(() -> BatchLoaderDefinition.byId(" ", new Recording("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchLoaderDefinition.grouped("Book", "", (k, m) -> Mono.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ RelationshipDefinition

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor BOOK = FILE.findMessageTypeByName("Book");
    private static final Descriptor AUTHOR = FILE.findMessageTypeByName("Author");

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/library.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addMessageType(
                            DescriptorProto.newBuilder().setName("Author").addField(string("id", 1)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(string("id", 1))
                            .addField(string("author_id", 2).setJsonName("authorId")))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder string(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    @Test
    @DisplayName(
            "GIVEN a to-one definition WHEN asked for the field it starts from SHOULD declare the companion with source BEAN")
    void GIVEN_toOneDefinition_WHEN_askedForItsField_SHOULD_declareCompanion() {
        RelationshipDefinition definition = RelationshipDefinition.one("Book.author_id", "Author");

        List<Relationship> relationships = definition.relationships(BOOK, BOOK.findFieldByName("author_id"));

        assertThat(definition.source()).isEqualTo(Source.BEAN);
        assertThat(relationships)
                .containsExactly(new Relationship("Book", "author", "authorId", "Author", "", Source.BEAN));
    }

    @Test
    @DisplayName(
            "GIVEN a to-many definition with a name WHEN asked SHOULD declare the list field grouped by the camelCase field")
    void GIVEN_toManyDefinition_WHEN_asked_SHOULD_declareListField() {
        RelationshipDefinition definition =
                RelationshipDefinition.many("Author.id", "Book", "author_id").named("works");

        List<Relationship> relationships = definition.relationships(AUTHOR, AUTHOR.findFieldByName("id"));

        assertThat(relationships)
                .containsExactly(new Relationship("Author", "works", "id", "Book", "authorId", Source.BEAN));
    }

    @Test
    @DisplayName("GIVEN a definition WHEN asked about another type or field SHOULD declare nothing")
    void GIVEN_definition_WHEN_askedAboutOtherField_SHOULD_declareNothing() {
        RelationshipDefinition definition = RelationshipDefinition.one("Book.author_id", "Author");

        assertThat(definition.relationships(BOOK, BOOK.findFieldByName("id"))).isEmpty();
        assertThat(definition.relationships(AUTHOR, AUTHOR.findFieldByName("id")))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN a malformed reference or a to-many without groupBy WHEN built SHOULD be rejected")
    void GIVEN_malformedDefinition_WHEN_built_SHOULD_beRejected() {
        assertThatThrownBy(() -> RelationshipDefinition.one("authorId", "Author"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RelationshipDefinition.one("Book.author_id", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RelationshipDefinition.many("Author.id", "Book", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
