package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Empty protobuf messages must yield valid GraphQL: an object or input type needs at least one
 * field. The fixture mirrors lbi-id-api ({@code UnlinkResponse {}}) and its admin empty responses.
 */
@ExtendWith(MockitoExtension.class)
class GraphQlEmptyMessageTest {

    @Mock
    private GrpcRouteRegistry registry;

    private static final FileDescriptor FILE = buildFile();

    @Test
    @DisplayName("GIVEN an empty response message WHEN emitting the object type SHOULD it has exactly the placeholder")
    void GIVEN_emptyResponse_WHEN_emittingObjectType_SHOULD_hasOnlyPlaceholder() throws Exception {
        for (String name : List.of(
                "UnlinkResponse",
                "UndoMergeResponse",
                "DisableServiceClientResponse",
                "DisableAccountResponse",
                "DeleteAccountResponse")) {
            Map<String, String> typeDefs = emitObject(name);

            assertThat(typeDefs.get(name)).isEqualTo("type " + name + " {\n  _placeholder: Boolean\n}\n");
        }
    }

    @Test
    @DisplayName("GIVEN an empty request message WHEN emitting the input type SHOULD it has an optional placeholder")
    void GIVEN_emptyRequest_WHEN_emittingInputType_SHOULD_hasOptionalPlaceholder() throws Exception {
        Map<String, String> typeDefs = emitInput("Options");

        assertThat(typeDefs.get("OptionsInput")).isEqualTo("input OptionsInput {\n  _placeholder: Boolean\n}\n");
    }

    @Test
    @DisplayName("GIVEN a message with real fields WHEN emitting it SHOULD no placeholder is added")
    void GIVEN_messageWithFields_WHEN_emitting_SHOULD_noPlaceholder() throws Exception {
        assertThat(emitObject("Link").get("Link")).doesNotContain("_placeholder");
        assertThat(emitInput("Link").get("LinkInput")).doesNotContain("_placeholder");
    }

    @Test
    @DisplayName("GIVEN a real field renamed to _placeholder WHEN emitting SHOULD the placeholder appears exactly once")
    void GIVEN_fieldNamedPlaceholder_WHEN_emitting_SHOULD_singlePlaceholder() throws Exception {
        String object = emitObject("Renamed").get("Renamed");
        String input = emitInput("Renamed").get("RenamedInput");

        assertThat(object).isEqualTo("type Renamed {\n  _placeholder: String\n}\n");
        assertThat(input).isEqualTo("input RenamedInput {\n  _placeholder: String\n}\n");
    }

    @Test
    @DisplayName("GIVEN a message with an empty nested message WHEN emitting SHOULD both types are valid")
    void GIVEN_nestedEmptyMessage_WHEN_emittingInput_SHOULD_nestedGetsPlaceholder() throws Exception {
        Map<String, String> typeDefs = emitInput("UnlinkRequest");

        assertThat(typeDefs.get("UnlinkRequestInput")).contains("options: OptionsInput");
        assertThat(typeDefs.get("OptionsInput")).contains("_placeholder: Boolean");
    }

    @Test
    @DisplayName("GIVEN google.protobuf.Empty fields WHEN emitting SHOULD they stay the Void scalar")
    void GIVEN_googleProtobufEmpty_WHEN_emitting_SHOULD_voidScalarUnchanged() throws Exception {
        assertThat(emitObject("WithEmpty").get("WithEmpty"))
                .contains("nothing: Void")
                .doesNotContain("_placeholder");
        assertThat(emitInput("WithEmpty").get("WithEmptyInput")).contains("nothing: Void");
    }

    @Test
    @DisplayName("GIVEN a nested placeholder argument WHEN stripping SHOULD only empty messages lose it")
    void GIVEN_placeholderArgs_WHEN_stripping_SHOULD_removedOnlyWhereMessageIsEmpty() {
        Descriptor request = FILE.findMessageTypeByName("UnlinkRequest");
        Descriptor renamed = FILE.findMessageTypeByName("Renamed");

        Map<String, Object> args = Map.of("id", "a", "options", Map.of("_placeholder", true));
        @SuppressWarnings("unchecked")
        Map<String, Object> stripped =
                (Map<String, Object>) DynamicGraphQlDataFetcher.stripEmptyPlaceholder(args, request);

        assertThat(stripped).containsEntry("id", "a").containsEntry("options", Map.of());

        Map<String, Object> real = Map.of("_placeholder", "kept");
        assertThat(DynamicGraphQlDataFetcher.stripEmptyPlaceholder(real, renamed))
                .isEqualTo(real);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> emitObject(String message) throws Exception {
        Map<String, String> typeDefs = new HashMap<>();
        Method m = GraphQlSchemaBuilder.class.getDeclaredMethod(
                "emitObjectType", Descriptor.class, Map.class, java.util.Set.class, int.class, Map.class);
        m.setAccessible(true);
        m.invoke(builder(), FILE.findMessageTypeByName(message), typeDefs, new HashSet<>(), 0, Map.of());
        return typeDefs;
    }

    private Map<String, String> emitInput(String message) throws Exception {
        Map<String, String> typeDefs = new HashMap<>();
        Method m = GraphQlSchemaBuilder.class.getDeclaredMethod(
                "emitInputType", Descriptor.class, String.class, Map.class, java.util.Set.class, int.class, Map.class);
        m.setAccessible(true);
        m.invoke(
                builder(),
                FILE.findMessageTypeByName(message),
                message + "Input",
                typeDefs,
                new HashSet<>(),
                0,
                Map.of());
        return typeDefs;
    }

    private GraphQlSchemaBuilder builder() {
        return new GraphQlSchemaBuilder(
                registry,
                new RelayConnectionTransformer(),
                new GraphQlPaginationArgInjector(),
                new GatewayProperties());
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static FileDescriptor buildFile() {
        try {
            FieldOptions renamedTo = FieldOptions.newBuilder()
                    .setExtension(
                            GraphQlProto.field,
                            FieldRule.newBuilder().setName("_placeholder").build())
                    .build();
            FileDescriptorProto.Builder proto = FileDescriptorProto.newBuilder()
                    .setName("id/empty.proto")
                    .setSyntax("proto3")
                    .setPackage("id")
                    .addDependency("shiguchi/graphql.proto")
                    .addDependency("google/protobuf/empty.proto");
            for (String name : List.of(
                    "UnlinkResponse",
                    "UndoMergeResponse",
                    "DisableServiceClientResponse",
                    "DisableAccountResponse",
                    "DeleteAccountResponse",
                    "Options")) {
                proto.addMessageType(DescriptorProto.newBuilder().setName(name));
            }
            proto.addMessageType(DescriptorProto.newBuilder()
                            .setName("Link")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("UnlinkRequest")
                            .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("options", 2, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".id.Options")))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Renamed")
                            .addField(field("real", 1, FieldDescriptorProto.Type.TYPE_STRING)
                                    .setOptions(renamedTo)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("WithEmpty")
                            .addField(field("nothing", 1, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".google.protobuf.Empty")));
            return FileDescriptor.buildFrom(proto.build(), new FileDescriptor[] {
                GraphQlProto.getDescriptor(), com.google.protobuf.EmptyProto.getDescriptor()
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
