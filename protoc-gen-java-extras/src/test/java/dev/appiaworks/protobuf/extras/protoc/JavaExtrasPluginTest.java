package dev.appiaworks.protobuf.extras.protoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.Edition;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JavaExtrasPluginTest {

    @Test
    @DisplayName(
            "Given message with one Date field in multiple_files mode, when generated, should insert into class_scope, builder_scope and message_implements")
    void given_messagewithDateField_when_generated_should_haveTwoFiles() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.acme").setJavaMultipleFiles(true))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Order")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("session_date")
                                .setNumber(1)
                                .setType(Type.TYPE_MESSAGE)
                                .setTypeName(".google.type.Date")
                                .setLabel(Label.LABEL_OPTIONAL)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        assertThat(response.getFileList()).hasSize(4);
        assertThat(response.getFileList())
                .allSatisfy(f -> assertThat(f.getName()).isEqualTo("com/acme/Order.java"));
        assertThat(response.getFile(0).getInsertionPoint()).isEqualTo("class_scope:acme.Order");
        assertThat(response.getFile(1).getInsertionPoint()).isEqualTo("builder_scope:acme.Order");
        assertThat(response.getFile(2).getInsertionPoint()).isEqualTo("message_implements:acme.Order");
        assertThat(response.getFile(2).getContent()).isEqualTo("dev.appiaworks.protobuf.extras.JsonWritable,\n");
        assertThat(response.getFile(3).getInsertionPoint()).isEqualTo("builder_implements:acme.Order");
        assertThat(response.getFile(3).getContent()).isEqualTo("dev.appiaworks.protobuf.extras.JsonReadable,\n");

        String classContent = response.getFile(0).getContent();
        assertThat(classContent).contains("getSessionDateAsLocalDate()");
        assertThat(classContent).contains("dev.appiaworks.protobuf.extras.ProtoTypes.toLocalDate");
        assertThat(classContent).doesNotContain("setSessionDate");
        assertThat(classContent)
                .contains("public void writeJson(com.fasterxml.jackson.core.JsonGenerator gen)")
                .contains("if (hasSessionDate()) {")
                .contains("gen.writeFieldName(\"sessionDate\");")
                .contains("dev.appiaworks.protobuf.extras.GeneratedJson.message(gen, getSessionDate());");

        String builderContent = response.getFile(1).getContent();
        assertThat(builderContent).contains("getSessionDateAsLocalDate()");
        assertThat(builderContent).contains("public Builder setSessionDate(java.time.LocalDate value)");
        assertThat(builderContent)
                .doesNotContain("writeJson")
                .contains("public Builder mergeJson(com.fasterxml.jackson.core.JsonParser p)")
                .contains("case \"sessionDate\", \"session_date\" -> {")
                .contains(
                        "dev.appiaworks.protobuf.extras.GeneratedJson.message(p, JsonFields.F[0], getSessionDateBuilder());");
    }

    @Test
    @DisplayName("Given message with no mapped fields, when generated, should insert the JSON writer and reader alone")
    void given_messageWithNoMappedFields_when_generated_should_insertTheJsonWriterAndReaderAlone() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Order")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("name")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        assertThat(response.getFileList()).hasSize(4);
        assertThat(response.getFile(0).getInsertionPoint()).isEqualTo("class_scope:acme.Order");
        assertThat(response.getFile(0).getContent())
                .contains("public void writeJson(")
                .contains("gen.writeFieldName(\"name\");")
                .contains("gen.writeString(getName());")
                .doesNotContain("if (has");
        assertThat(response.getFile(0).getContent()).contains("private static final class JsonFields {");
        assertThat(response.getFile(1).getInsertionPoint()).isEqualTo("builder_scope:acme.Order");
        assertThat(response.getFile(1).getContent())
                .contains("public Builder mergeJson(")
                .contains("case \"name\" -> {")
                .contains(
                        "java.lang.Object v = dev.appiaworks.protobuf.extras.serde.StrictScalars.read(p, JsonFields.F[0]);")
                .contains("if (v != null) setName((java.lang.String) v);")
                .contains("default -> p.skipChildren();");
        assertThat(response.getFile(2).getInsertionPoint()).isEqualTo("message_implements:acme.Order");
        assertThat(response.getFile(3).getInsertionPoint()).isEqualTo("builder_implements:acme.Order");
    }

    @Test
    @DisplayName("Given json=off, when generated, should insert no JSON writer or reader")
    void given_jsonOff_when_generated_should_insertNoJsonWriterOrReader() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Order")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("name")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .setParameter("json=off")
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        assertThat(response.getFileList()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given a message reaching google.protobuf.Any, when generated, should insert no JSON writer or reader for it")
    void given_messageReachingAny_when_generated_should_insertNoJsonWriterOrReaderForIt() {
        FileDescriptorProto any = FileDescriptorProto.newBuilder()
                .setName("google/protobuf/any.proto")
                .setPackage("google.protobuf")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Any")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("type_url")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("value")
                                .setNumber(2)
                                .setType(Type.TYPE_BYTES)))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .addDependency("google/protobuf/any.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Order")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("payload")
                                .setNumber(1)
                                .setType(Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Any")
                                .setLabel(Label.LABEL_OPTIONAL)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Plain")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("name")
                                .setNumber(1)
                                .setType(Type.TYPE_STRING)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(any)
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        assertThat(response.getFileList())
                .extracting(f -> f.getInsertionPoint())
                .containsExactly(
                        "class_scope:acme.Plain",
                        "builder_scope:acme.Plain",
                        "message_implements:acme.Plain",
                        "builder_implements:acme.Plain");
    }

    @Test
    @DisplayName("Given file in proto_file but not in file_to_generate, when generated, should produce no files")
    void given_fileNotInFileToGenerate_when_generated_should_produceNoFiles() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Order")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("session_date")
                                .setNumber(1)
                                .setType(Type.TYPE_MESSAGE)
                                .setTypeName(".google.type.Date")
                                .setLabel(Label.LABEL_OPTIONAL)))
                .build();

        CodeGeneratorRequest request =
                CodeGeneratorRequest.newBuilder().addProtoFile(file).build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        assertThat(response.getFileList()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given message with repeated Date field, when generated, should have builder_scope with addHolidays and class_scope with getHolidaysAsLocalDateList")
    void given_repeatedDateField_when_generated_should_haveRepeatedAccessors() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/calendar.proto")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.acme").setJavaMultipleFiles(true))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Calendar")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("holidays")
                                .setNumber(1)
                                .setType(Type.TYPE_MESSAGE)
                                .setTypeName(".google.type.Date")
                                .setLabel(Label.LABEL_REPEATED)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/calendar.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        assertThat(response.getFileList()).hasSize(4);
        String builderContent = response.getFile(1).getContent();
        assertThat(builderContent).contains("addHolidays(java.time.LocalDate value)");
        assertThat(builderContent).contains("addAllHolidaysAsLocalDate(");

        String classContent = response.getFile(0).getContent();
        assertThat(classContent).contains("getHolidaysAsLocalDateList()");
    }

    @Test
    @DisplayName("Given nested message with Date field, when generated, should have insertion point for nested message")
    void given_nestedMessageWithDateField_when_generated_should_haveNestedInsertionPoint() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.acme").setJavaMultipleFiles(true))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Order")
                        .addNestedType(DescriptorProto.newBuilder()
                                .setName("Line")
                                .addField(FieldDescriptorProto.newBuilder()
                                        .setName("ship_date")
                                        .setNumber(1)
                                        .setType(Type.TYPE_MESSAGE)
                                        .setTypeName(".google.type.Date")
                                        .setLabel(Label.LABEL_OPTIONAL))))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        boolean hasNestedInsertionPoint = response.getFileList().stream()
                .anyMatch(f -> f.getInsertionPoint().equals("class_scope:acme.Order.Line")
                        && f.getName().equals("com/acme/Order.java"));
        assertThat(hasNestedInsertionPoint).isTrue();
    }

    @Test
    @DisplayName(
            "Given message with Timestamp field, when generated, should have two getters AsInstant and AsOffsetDateTime")
    void given_timestampField_when_generated_should_haveTwoGetters() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/event.proto")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.acme").setJavaMultipleFiles(true))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Event")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("created_at")
                                .setNumber(1)
                                .setType(Type.TYPE_MESSAGE)
                                .setTypeName(".google.protobuf.Timestamp")
                                .setLabel(Label.LABEL_OPTIONAL)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/event.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        String classContent = response.getFile(0).getContent();
        assertThat(classContent).contains("AsInstant()");
        assertThat(classContent).contains("AsOffsetDateTime()");
    }

    @Test
    @DisplayName(
            "Given message with enum field of type DayOfWeek, when generated, should have getter with AsJavaDayOfWeek and no hasWeekday")
    void given_enumField_when_generated_should_haveEnumGetter() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/schedule.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.acme").setJavaMultipleFiles(true))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Schedule")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("weekday")
                                .setNumber(1)
                                .setType(Type.TYPE_ENUM)
                                .setTypeName(".google.type.DayOfWeek")
                                .setLabel(Label.LABEL_OPTIONAL)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/schedule.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        String classContent = response.getFile(0).getContent();
        assertThat(classContent).contains("getWeekdayAsJavaDayOfWeek()");
        assertThat(classContent).doesNotContain("hasWeekday()");
    }

    @Test
    @DisplayName("Given parameter with disable=google.type.Date, when generated, should produce no accessors")
    void given_disableOption_when_generated_should_produceNoAccessors() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.acme").setJavaMultipleFiles(true))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Order")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("session_date")
                                .setNumber(1)
                                .setType(Type.TYPE_MESSAGE)
                                .setTypeName(".google.type.Date")
                                .setLabel(Label.LABEL_OPTIONAL)))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .setParameter("disable=google.type.Date")
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        assertThat(response.getFileList())
                .extracting(f -> f.getInsertionPoint())
                .containsExactly(
                        "class_scope:acme.Order",
                        "builder_scope:acme.Order",
                        "message_implements:acme.Order",
                        "builder_implements:acme.Order");
        assertThat(response.getFile(0).getContent())
                .doesNotContain("AsLocalDate")
                .contains("writeJson");
        assertThat(response.getFile(1).getContent())
                .doesNotContain("AsLocalDate")
                .contains("mergeJson");
    }

    @Test
    @DisplayName(
            "Given a response, when checked for supported features, should have proto3_optional and editions support")
    void given_response_when_checkedFeatures_should_supportProto3OptionalAndEditions() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .addMessageType(DescriptorProto.newBuilder().setName("Order"))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(file)
                .build();

        CodeGeneratorResponse response = JavaExtrasPlugin.generate(request);

        long features = response.getSupportedFeatures();
        assertThat(features & CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL_VALUE)
                .isNotZero();
        assertThat(features & CodeGeneratorResponse.Feature.FEATURE_SUPPORTS_EDITIONS_VALUE)
                .isNotZero();
        assertThat(response.getMaximumEdition()).isEqualTo(Edition.EDITION_2023_VALUE);
    }

    @Test
    @DisplayName("Given unknown option in parameter, when generated, should throw IllegalArgumentException")
    void given_unknownOption_when_generated_should_throw() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setSyntax("proto3")
                .setPackage("acme")
                .addMessageType(DescriptorProto.newBuilder().setName("Order"))
                .build();

        CodeGeneratorRequest request = CodeGeneratorRequest.newBuilder()
                .setParameter("frobnicate=x")
                .addFileToGenerate("acme/order.proto")
                .addProtoFile(file)
                .build();

        assertThatThrownBy(() -> JavaExtrasPlugin.generate(request)).isInstanceOf(IllegalArgumentException.class);
    }
}
