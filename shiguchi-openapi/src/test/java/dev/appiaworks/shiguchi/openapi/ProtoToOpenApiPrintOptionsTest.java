package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import io.swagger.v3.oas.models.media.Schema;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for ProtoToOpenApiSchemaConverter with JsonPrintOptions.
 *
 * Tests that response schemas follow per-service JsonPrintOptions: proto field names under
 * preserveProtoFieldNames, enum numbers under enumsAsInts, and appropriate component name suffixes.
 * Nested message fields follow the same options. Request schemas always use default options.
 */
@DisplayName("ProtoToOpenApiSchemaConverter — per-service JSON print options")
class ProtoToOpenApiPrintOptionsTest {

    private static final Descriptor BOOK = buildBook();

    private final ProtoToOpenApiSchemaConverter converter = new ProtoToOpenApiSchemaConverter();

    @AfterEach
    void restoreDefaults() {
        JsonPrintOptions.install(JsonPrintOptions.DEFAULT);
    }

    @Test
    @DisplayName(
            "GIVEN default JSON print options WHEN schema is built for Book SHOULD produce component named 'Book' with lowerCamelCase field names")
    void given_defaultPrintOptions_when_schemaBuilt_should_beUnsuffixedWithCamelCase() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();
        JsonPrintOptions options = JsonPrintOptions.DEFAULT;

        // Act
        Schema<?> schema = converter.responseSchemaForDescriptor(BOOK, components, visited, options);

        // Assert
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/Book");
        assertThat(components).containsKey("Book");
        Map<String, Schema> properties = components.get("Book").getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder("displayName", "format", "mainAuthor");
    }

    @Test
    @DisplayName(
            "GIVEN preserveProtoFieldNames=true WHEN schema is built for Book SHOULD produce component named 'Book_proto_names' with snake_case field names")
    void given_preserveProtoFieldNames_when_schemaBuilt_should_beProtoNamesSuffixed() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();
        JsonPrintOptions options = new JsonPrintOptions(true, false, false);

        // Act
        Schema<?> schema = converter.responseSchemaForDescriptor(BOOK, components, visited, options);

        // Assert
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/Book_proto_names");
        assertThat(components).containsKey("Book_proto_names");
        Map<String, Schema> properties = components.get("Book_proto_names").getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder("display_name", "format", "main_author");
    }

    @Test
    @DisplayName(
            "GIVEN preserveProtoFieldNames=true WHEN nested message field is resolved SHOULD reference nested component with same suffix")
    void given_preserveProtoFieldNames_when_nestedMessageResolved_should_nestedComponentHasSameSuffix() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();
        JsonPrintOptions options = new JsonPrintOptions(true, false, false);

        // Act
        converter.responseSchemaForDescriptor(BOOK, components, visited, options);

        // Assert
        assertThat(components).containsKeys("Book_proto_names", "Author_proto_names");
        Map<String, Schema> bookProperties = components.get("Book_proto_names").getProperties();
        Schema mainAuthorField = bookProperties.get("main_author");
        assertThat(mainAuthorField.get$ref()).isEqualTo("#/components/schemas/Author_proto_names");

        Map<String, Schema> authorProperties =
                components.get("Author_proto_names").getProperties();
        assertThat(authorProperties.keySet()).containsExactly("full_name");
    }

    @Test
    @DisplayName(
            "GIVEN enumsAsInts=true WHEN schema is built for Book SHOULD produce component named 'Book_enums_as_ints' with enum field as integer")
    void given_enumsAsInts_when_schemaBuilt_should_beEnumsAsSuffixed() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();
        JsonPrintOptions options = new JsonPrintOptions(false, true, false);

        // Act
        Schema<?> schema = converter.responseSchemaForDescriptor(BOOK, components, visited, options);

        // Assert
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/Book_enums_as_ints");
        assertThat(components).containsKey("Book_enums_as_ints");
        Map<String, Schema> properties = components.get("Book_enums_as_ints").getProperties();
        Schema formatField = properties.get("format");
        assertThat(formatField.getType()).isEqualTo("integer");
        assertThat(formatField.getFormat()).isEqualTo("int32");
        assertThat(formatField.getEnum()).containsExactlyInAnyOrder(0, 1);
        assertThat(formatField.getDescription()).contains("0 = UNKNOWN", "1 = HARDCOVER");
    }

    @Test
    @DisplayName(
            "GIVEN preserveProtoFieldNames=true and enumsAsInts=true WHEN schema is built for Book SHOULD produce component named 'Book_proto_names_enums_as_ints'")
    void given_protoNamesAndEnumsAsInts_when_schemaBuilt_should_beBothSuffixed() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();
        JsonPrintOptions options = new JsonPrintOptions(true, true, false);

        // Act
        Schema<?> schema = converter.responseSchemaForDescriptor(BOOK, components, visited, options);

        // Assert
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/Book_proto_names_enums_as_ints");
        assertThat(components).containsKey("Book_proto_names_enums_as_ints");
        Map<String, Schema> properties =
                components.get("Book_proto_names_enums_as_ints").getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder("display_name", "format", "main_author");

        Schema formatField = properties.get("format");
        assertThat(formatField.getType()).isEqualTo("integer");
        assertThat(formatField.getEnum()).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    @DisplayName(
            "GIVEN omitDefaultValues=true only WHEN schema is built for Book SHOULD produce component named 'Book' without suffix")
    void given_omitDefaultValuesOnly_when_schemaBuilt_should_beUnsuffixed() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();
        JsonPrintOptions options = new JsonPrintOptions(false, false, true);

        // Act
        Schema<?> schema = converter.responseSchemaForDescriptor(BOOK, components, visited, options);

        // Assert
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/Book");
        assertThat(components).containsKey("Book");
        Map<String, Schema> properties = components.get("Book").getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder("displayName", "format", "mainAuthor");
    }

    @Test
    @DisplayName("GIVEN null print options WHEN schema is built for Book SHOULD treat as DEFAULT options")
    void given_nullPrintOptions_when_schemaBuilt_should_treatAsDefault() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();

        // Act
        Schema<?> schema = converter.responseSchemaForDescriptor(BOOK, components, visited, null);

        // Assert
        assertThat(schema.get$ref()).isEqualTo("#/components/schemas/Book");
        assertThat(components).containsKey("Book");
        Map<String, Schema> properties = components.get("Book").getProperties();
        assertThat(properties.keySet()).containsExactlyInAnyOrder("displayName", "format", "mainAuthor");
    }

    @Test
    @DisplayName(
            "GIVEN same components map used for default request and proto-names response WHEN schemas are built SHOULD both 'Book' and 'Book_proto_names' be present")
    void given_sameComponentsMapForDefaultAndProtoNames_when_schemasBuilt_should_bothComponentsPresent() {
        // Arrange
        Map<String, Schema<?>> components = new HashMap<>();
        Set<String> visited = new HashSet<>();

        // Act: Build request schema (default options)
        Schema<?> requestSchema = converter.schemaForDescriptor(BOOK, components, visited);
        // Build response schema (proto-names options)
        Schema<?> responseSchema = converter.responseSchemaForDescriptor(
                BOOK, components, visited, new JsonPrintOptions(true, false, false));

        // Assert
        assertThat(requestSchema.get$ref()).isEqualTo("#/components/schemas/Book");
        assertThat(responseSchema.get$ref()).isEqualTo("#/components/schemas/Book_proto_names");
        assertThat(components).containsKeys("Book", "Book_proto_names");

        Map<String, Schema> bookProperties = components.get("Book").getProperties();
        assertThat(bookProperties.keySet()).containsExactlyInAnyOrder("displayName", "format", "mainAuthor");

        Map<String, Schema> protoNamesProperties =
                components.get("Book_proto_names").getProperties();
        assertThat(protoNamesProperties.keySet()).containsExactlyInAnyOrder("display_name", "format", "main_author");
    }

    // -------------------------------------------------------------------------
    // Descriptor builders
    // -------------------------------------------------------------------------

    private static Descriptor buildBook() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("book.proto")
                .setPackage("test")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Book")
                        .addField(field("display_name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("format")
                                .setNumber(2)
                                .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                                .setTypeName("Format"))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("main_author")
                                .setNumber(3)
                                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName("Author"))
                        .build())
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Author")
                        .addField(field("full_name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .build())
                .addEnumType(EnumDescriptorProto.newBuilder()
                        .setName("Format")
                        .addValue(EnumValueDescriptorProto.newBuilder()
                                .setName("UNKNOWN")
                                .setNumber(0)
                                .build())
                        .addValue(EnumValueDescriptorProto.newBuilder()
                                .setName("HARDCOVER")
                                .setNumber(1)
                                .build())
                        .build())
                .build();

        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {}).findMessageTypeByName("Book");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
