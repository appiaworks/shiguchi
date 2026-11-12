package dev.appiaworks.shiguchi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProtoProjector")
class ProtoProjectorTest {

    enum Genre {
        SCIFI,
        DRAMA
    }

    /** proto3: message Book { string id; string title; int32 published_year; int64 pages; double rating;
     *  bool in_print; bytes cover; Genre genre; Timestamp added_at; repeated string tags; Book sequel; } */
    private static final Descriptor BOOK = buildBook();

    private static Descriptor buildBook() {
        try {
            FileDescriptor timestamp = Timestamp.getDescriptor().getFile();
            FieldDescriptorProto.Builder b = FieldDescriptorProto.newBuilder();
            DescriptorProto message = DescriptorProto.newBuilder()
                    .setName("Book")
                    .addField(field("id", 1, Type.TYPE_STRING))
                    .addField(field("title", 2, Type.TYPE_STRING))
                    .addField(field("published_year", 3, Type.TYPE_INT32))
                    .addField(field("pages", 4, Type.TYPE_INT64))
                    .addField(field("rating", 5, Type.TYPE_DOUBLE))
                    .addField(field("in_print", 6, Type.TYPE_BOOL))
                    .addField(field("cover", 7, Type.TYPE_BYTES))
                    .addField(field("genre", 8, Type.TYPE_ENUM).setTypeName(".t.Genre"))
                    .addField(field("added_at", 9, Type.TYPE_MESSAGE).setTypeName(".google.protobuf.Timestamp"))
                    .addField(field("tags", 10, Type.TYPE_STRING).setLabel(Label.LABEL_REPEATED))
                    .addField(field("sequel", 11, Type.TYPE_MESSAGE).setTypeName(".t.Book"))
                    .build();
            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("t.proto")
                    .setPackage("t")
                    .setSyntax("proto3")
                    .addDependency("google/protobuf/timestamp.proto")
                    .addMessageType(message)
                    .addEnumType(EnumDescriptorProto.newBuilder()
                            .setName("Genre")
                            .addValue(com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                    .setName("GENRE_UNSPECIFIED")
                                    .setNumber(0))
                            .addValue(com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                    .setName("SCIFI")
                                    .setNumber(1))
                            .addValue(com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                    .setName("DRAMA")
                                    .setNumber(2)))
                    .build();
            return FileDescriptor.buildFrom(file, new FileDescriptor[] {timestamp})
                    .findMessageTypeByName("Book");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(Label.LABEL_OPTIONAL);
    }

    private final ProtoProjector<DynamicMessage> projector = ProtoProjector.of(DynamicMessage.getDefaultInstance(BOOK));

    private Object get(DynamicMessage m, String field) {
        return m.getField(BOOK.findFieldByName(field));
    }

    @Test
    @DisplayName("GIVEN scalars of looser Java types WHEN projected SHOULD convert numbers, strings and booleans")
    void given_scalars_when_project_should_convert() {
        DynamicMessage book = projector.project(Map.of(
                "id",
                7,
                "title",
                "Dune",
                "published_year",
                (short) 1965,
                "pages",
                612,
                "rating",
                4.5f,
                "in_print",
                true));

        assertThat(get(book, "id")).isEqualTo("7");
        assertThat(get(book, "title")).isEqualTo("Dune");
        assertThat(get(book, "published_year")).isEqualTo(1965);
        assertThat(get(book, "pages")).isEqualTo(612L);
        assertThat(get(book, "rating")).isEqualTo(4.5);
        assertThat(get(book, "in_print")).isEqualTo(true);
    }

    @Test
    @DisplayName("GIVEN camelCase keys WHEN projected SHOULD match the snake_case fields")
    void given_camelCaseKeys_when_project_should_match() {
        DynamicMessage book = projector.project(Map.of("publishedYear", 1965, "inPrint", true));

        assertThat(get(book, "published_year")).isEqualTo(1965);
        assertThat(get(book, "in_print")).isEqualTo(true);
    }

    @Test
    @DisplayName("GIVEN a Java enum, a name and a number WHEN projected SHOULD resolve the proto enum value")
    void given_enumForms_when_project_should_resolve() {
        for (Object genre : new Object[] {Genre.SCIFI, "SCIFI", 1}) {
            DynamicMessage book = projector.project(Map.of("genre", genre));
            assertThat(((com.google.protobuf.Descriptors.EnumValueDescriptor) get(book, "genre")).getName())
                    .isEqualTo("SCIFI");
        }
    }

    @Test
    @DisplayName("GIVEN an unknown enum name WHEN projected SHOULD fail naming the field")
    void given_unknownEnum_when_project_should_fail() {
        assertThatThrownBy(() -> projector.project(Map.of("genre", "WESTERN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t.Book.genre");
    }

    @Test
    @DisplayName("GIVEN an Instant and bytes WHEN projected SHOULD build a Timestamp and a ByteString")
    void given_timeAndBytes_when_project_should_convert() throws Exception {
        Instant at = Instant.parse("2026-11-11T10:15:30.123456789Z");

        DynamicMessage book = projector.project(Map.of("added_at", at, "cover", new byte[] {1, 2, 3}));

        Timestamp ts = (Timestamp) get(book, "added_at");
        assertThat(ts.getSeconds()).isEqualTo(at.getEpochSecond());
        assertThat(ts.getNanos()).isEqualTo(at.getNano());
        assertThat(((com.google.protobuf.ByteString) get(book, "cover")).toByteArray())
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("GIVEN a list WHEN projected into a repeated field SHOULD add every element")
    void given_list_when_project_should_fillRepeated() {
        DynamicMessage book = projector.project(Map.of("tags", List.of("classic", "desert")));

        assertThat(((List<?>) get(book, "tags")).stream().map(Object::toString).toList())
                .containsExactly("classic", "desert");
    }

    @Test
    @DisplayName("GIVEN a null value WHEN projected SHOULD leave the field unset")
    void given_null_when_project_should_leaveUnset() {
        Map<String, Object> values = new HashMap<>();
        values.put("title", null);
        values.put("id", "1");

        DynamicMessage book = projector.project(values);

        assertThat(book.hasField(BOOK.findFieldByName("title"))).isFalse();
        assertThat(get(book, "id")).isEqualTo("1");
    }

    @Test
    @DisplayName("GIVEN a key that names no field WHEN projected SHOULD fail loudly")
    void given_unknownKey_when_project_should_fail() {
        assertThatThrownBy(() -> projector.project(Map.of("ghost", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    @DisplayName("GIVEN a nested message with no mapper WHEN projected SHOULD ask for one")
    void given_nestedWithoutMapper_when_project_should_askForMapper() {
        assertThatThrownBy(() -> projector.project(Map.of("sequel", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapper");
    }

    @Test
    @DisplayName("GIVEN a mapper for a field WHEN projected SHOULD use it instead of the built-in conversion")
    void given_mapper_when_project_should_useIt() {
        FieldDescriptor title = BOOK.findFieldByName("title");
        ProtoProjector<DynamicMessage> custom = ProtoProjector.builder(DynamicMessage.getDefaultInstance(BOOK))
                .mapper("title", (b, v) -> b.setField(title, v.toString().toUpperCase()))
                .build();

        assertThat(get(custom.project(Map.of("title", "dune")), "title")).isEqualTo("DUNE");
    }

    @Test
    @DisplayName("GIVEN a wrongly typed value WHEN projected SHOULD name the field and the type")
    void given_wrongType_when_project_should_explain() {
        assertThatThrownBy(() -> projector.project(Map.of("published_year", "nineteen")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("published_year")
                .hasMessageContaining("String");
    }
}
