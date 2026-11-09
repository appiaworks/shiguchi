package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.TypeRule;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** SDL emission of the federation field options, {@code (shiguchi.type)} and raw directives. */
class GraphQlFederationOptionsTest {

    @Test
    @DisplayName("GIVEN federation field options WHEN building the schema SHOULD emit the directives")
    void given_federation_field_options_when_building_the_schema_should_emit_the_directives() throws Exception {
        Descriptor book = book(
                TypeRule.newBuilder()
                        .addKeys("id")
                        .setDescription("A book")
                        .addDirectives("@tag(name: \"internal\")")
                        .build(),
                FieldRule.newBuilder()
                        .setShareable(true)
                        .setExternal(true)
                        .setRequires("title")
                        .setProvides("id")
                        .addDirectives("@tag(name: \"f\")")
                        .build());
        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, String> typeDefs = new HashMap<>();

        emit(book, typeDefs, keys);

        assertThat(typeDefs.get("Book"))
                .contains("\"A book\"\ntype Book @key(fields: \"id\") @tag(name: \"internal\") {")
                .contains("note: String @shareable @external @requires(fields: \"title\")"
                        + " @provides(fields: \"id\") @tag(name: \"f\")");
        assertThat(keys).containsEntry("Book", "id");
    }

    @Test
    @DisplayName("GIVEN type keys WHEN building the schema SHOULD override the batch_key fallback")
    void given_type_keys_when_building_the_schema_should_override_the_batch_key_fallback() throws Exception {
        Descriptor book = book(
                TypeRule.newBuilder().addKeys("title").addKeys("id title").build(), FieldRule.getDefaultInstance());
        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, String> typeDefs = new HashMap<>();

        emit(book, typeDefs, keys);

        assertThat(typeDefs.get("Book")).contains("@key(fields: \"title\") @key(fields: \"id title\")");
        assertThat(keys).containsEntry("Book", "title");
    }

    @Test
    @DisplayName("GIVEN an unresolvable type WHEN building the schema SHOULD mark the key and skip entity resolution")
    void given_an_unresolvable_type_when_building_the_schema_should_mark_the_key_and_skip_entity_resolution()
            throws Exception {
        Descriptor book =
                book(TypeRule.newBuilder().addKeys("id").setUnresolvable(true).build(), FieldRule.getDefaultInstance());
        Map<String, String> keys = new LinkedHashMap<>();
        Map<String, String> typeDefs = new HashMap<>();

        emit(book, typeDefs, keys);

        assertThat(typeDefs.get("Book")).contains("@key(fields: \"id\", resolvable: false)");
        assertThat(keys).doesNotContainKey("Book");
    }

    @Test
    @DisplayName("GIVEN an invalid requires selection WHEN building the schema SHOULD reject it")
    void given_an_invalid_requires_selection_when_building_the_schema_should_reject_it() throws Exception {
        Descriptor book = book(
                TypeRule.getDefaultInstance(),
                FieldRule.newBuilder().setRequires("title {").build());

        assertThatThrownBy(() -> emit(book, new HashMap<>(), new LinkedHashMap<>()))
                .isInstanceOf(InvocationTargetException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Book.note")
                .hasMessageContaining("requires");
    }

    @Test
    @DisplayName("GIVEN federation off WHEN a field sets shareable SHOULD ignore it and keep raw directives")
    void given_federation_off_when_a_field_sets_shareable_should_ignore_it_and_keep_raw_directives() throws Exception {
        Descriptor book = book(
                TypeRule.getDefaultInstance(),
                FieldRule.newBuilder()
                        .setShareable(true)
                        .addDirectives("@tag(name: \"f\")")
                        .build());
        Map<String, String> typeDefs = new HashMap<>();

        emit(book, typeDefs, null);

        assertThat(typeDefs.get("Book"))
                .contains("note: String @tag(name: \"f\")")
                .doesNotContain("@shareable");
    }

    // -------------------------------------------------------------------------

    private static void emit(Descriptor descriptor, Map<String, String> typeDefs, Map<String, String> keys)
            throws Exception {
        GraphQlSchemaBuilder builder = new GraphQlSchemaBuilder(
                Mockito.mock(GrpcRouteRegistry.class),
                new RelayConnectionTransformer(),
                new GraphQlPaginationArgInjector(),
                new GatewayProperties());
        Method m = GraphQlSchemaBuilder.class.getDeclaredMethod(
                "emitObjectType", Descriptor.class, Map.class, java.util.Set.class, int.class, Map.class, Map.class);
        m.setAccessible(true);
        m.invoke(builder, descriptor, typeDefs, new HashSet<>(), 0, Map.of(), keys);
    }

    /** {@code message Book { int64 id = 1; string title = 2; string note = 3 [rule]; }} with the type rule. */
    private static Descriptor book(TypeRule typeRule, FieldRule noteRule) throws Exception {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("book.proto")
                .setSyntax("proto3")
                .addDependency("shiguchi/graphql.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Book")
                        .setOptions(MessageOptions.newBuilder().setExtension(GraphQlProto.type, typeRule))
                        .addField(
                                field("id", 1, FieldDescriptorProto.Type.TYPE_INT64, FieldOptions.getDefaultInstance()))
                        .addField(field(
                                "title", 2, FieldDescriptorProto.Type.TYPE_STRING, FieldOptions.getDefaultInstance()))
                        .addField(field(
                                "note",
                                3,
                                FieldDescriptorProto.Type.TYPE_STRING,
                                FieldOptions.newBuilder()
                                        .setExtension(GraphQlProto.field, noteRule)
                                        .build())))
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[] {GraphQlProto.getDescriptor()})
                .findMessageTypeByName("Book");
    }

    private static FieldDescriptorProto field(
            String name, int number, FieldDescriptorProto.Type type, FieldOptions options) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .setOptions(options)
                .build();
    }
}
