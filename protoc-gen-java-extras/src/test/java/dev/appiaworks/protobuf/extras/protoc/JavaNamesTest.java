package dev.appiaworks.protobuf.extras.protoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JavaNamesTest {

    @ParameterizedTest
    @CsvSource({
        "session_date, SessionDate",
        "name, Name",
        "line_2x_date, Line2XDate",
        "foo1bar, Foo1Bar",
        "URL, URL",
        "already_Camel, AlreadyCamel",
        "class, Class_",
        "a__b, AB"
    })
    @DisplayName(
            "Given field name with various patterns, when converted to accessor name, should follow protoc conventions")
    void given_fieldName_when_convertedToAccessorName_should_followProtocConventions(
            String fieldName, String expected) {
        assertThat(JavaNames.accessorName(fieldName)).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given FileDescriptorProto with java_package option set, when getting javaPackage, should return the option value")
    void given_javaPackageOptionSet_when_getJavaPackage_should_returnOption() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("com.example")
                .setOptions(FileOptions.newBuilder().setJavaPackage("com.acme.proto"))
                .build();

        assertThat(JavaNames.javaPackage(file)).isEqualTo("com.acme.proto");
    }

    @Test
    @DisplayName(
            "Given FileDescriptorProto without java_package option, when getting javaPackage, should return proto package")
    void given_noJavaPackageOption_when_getJavaPackage_should_returnProtoPackage() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("com.example.proto")
                .build();

        assertThat(JavaNames.javaPackage(file)).isEqualTo("com.example.proto");
    }

    @Test
    @DisplayName(
            "Given FileDescriptorProto with neither java_package nor proto package, when getting javaPackage, should return empty string")
    void given_noJavaPackageAndNoProtoPackage_when_getJavaPackage_should_returnEmpty() {
        FileDescriptorProto file =
                FileDescriptorProto.newBuilder().setName("test.proto").build();

        assertThat(JavaNames.javaPackage(file)).isEmpty();
    }

    @Test
    @DisplayName(
            "Given FileDescriptorProto with java_outer_classname option set, when getting outerClassName, should return the option value")
    void given_javaOuterClassnameOptionSet_when_getOuterClassName_should_returnOption() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setOptions(FileOptions.newBuilder().setJavaOuterClassname("MyOuterClass"))
                .build();

        assertThat(JavaNames.outerClassName(file)).isEqualTo("MyOuterClass");
    }

    @Test
    @DisplayName(
            "Given file with no clash when deriving class name from file name, when getting outerClassName, should not add OuterClass suffix")
    void given_noClash_when_getOuterClassName_should_notAddSuffix() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("accessors/legacy_order.proto")
                .build();

        assertThat(JavaNames.outerClassName(file)).isEqualTo("LegacyOrder");
    }

    @Test
    @DisplayName(
            "Given file with top-level message having same name as derived class name, when getting outerClassName, should add OuterClass suffix")
    void given_clashWithTopLevelMessage_when_getOuterClassName_should_addSuffix() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("accessors/legacy_order.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("LegacyOrder"))
                .build();

        assertThat(JavaNames.outerClassName(file)).isEqualTo("LegacyOrderOuterClass");
    }

    @Test
    @DisplayName(
            "Given file with top-level enum having same name as derived class name, when getting outerClassName, should add OuterClass suffix")
    void given_clashWithTopLevelEnum_when_getOuterClassName_should_addSuffix() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("accessors/legacy_order.proto")
                .addEnumType(EnumDescriptorProto.newBuilder().setName("LegacyOrder"))
                .build();

        assertThat(JavaNames.outerClassName(file)).isEqualTo("LegacyOrderOuterClass");
    }

    @Test
    @DisplayName(
            "Given file with top-level service having same name as derived class name, when getting outerClassName, should add OuterClass suffix")
    void given_clashWithTopLevelService_when_getOuterClassName_should_addSuffix() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("accessors/legacy_order.proto")
                .addService(ServiceDescriptorProto.newBuilder().setName("LegacyOrder"))
                .build();

        assertThat(JavaNames.outerClassName(file)).isEqualTo("LegacyOrderOuterClass");
    }

    @Test
    @DisplayName(
            "Given file with nested message having same name as derived class name, when getting outerClassName, should add OuterClass suffix")
    void given_clashWithNestedMessage_when_getOuterClassName_should_addSuffix() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("accessors/legacy_order.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Outer")
                        .addNestedType(DescriptorProto.newBuilder().setName("LegacyOrder")))
                .build();

        assertThat(JavaNames.outerClassName(file)).isEqualTo("LegacyOrderOuterClass");
    }

    @Test
    @DisplayName(
            "Given file with java_multiple_files true, when getting javaFile for message, should return message file path with package dir")
    void given_javaMultipleFilesTrue_when_getJavaFile_should_returnMessageFilePath() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaMultipleFiles(true).setJavaPackage("com.acme.proto"))
                .build();

        assertThat(JavaNames.javaFile(file, "Order")).isEqualTo("com/acme/proto/Order.java");
    }

    @Test
    @DisplayName(
            "Given file with java_multiple_files false, when getting javaFile for message, should return outer class file path")
    void given_javaMultipleFilesFalse_when_getJavaFile_should_returnOuterClassFilePath() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/trip_api.proto")
                .setPackage("acme")
                .setOptions(FileOptions.newBuilder().setJavaMultipleFiles(false).setJavaPackage("com.acme.proto"))
                .build();

        assertThat(JavaNames.javaFile(file, "Trip")).isEqualTo("com/acme/proto/TripApi.java");
    }

    @Test
    @DisplayName(
            "Given file with no java_package and proto package set, when getting javaFile, should use proto package directory")
    void given_noJavaPackageWithProtoPackage_when_getJavaFile_should_useProtoPackageDir() {
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("acme/order.proto")
                .setPackage("acme.v1")
                .build();

        String javaFile = JavaNames.javaFile(file, "Order");
        assertThat(javaFile).startsWith("acme/v1/");
    }

    @Test
    @DisplayName("Given file with no package at all, when getting javaFile, should have no leading slash")
    void given_noPackage_when_getJavaFile_should_haveNoLeadingSlash() {
        FileDescriptorProto file =
                FileDescriptorProto.newBuilder().setName("order.proto").build();

        assertThat(JavaNames.javaFile(file, "Order")).isEqualTo("Order.java");
    }
}
