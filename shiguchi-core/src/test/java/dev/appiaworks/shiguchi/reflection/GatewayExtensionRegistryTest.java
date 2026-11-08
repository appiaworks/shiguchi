package dev.appiaworks.shiguchi.reflection;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.UnknownFieldSet;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.MergeProto;
import dev.appiaworks.shiguchi.proto.MergeRule;
import dev.appiaworks.shiguchi.proto.MethodProto;
import dev.appiaworks.shiguchi.proto.MethodRule;
import dev.appiaworks.shiguchi.proto.TopicProto;
import dev.appiaworks.shiguchi.proto.UpstreamTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for GatewayExtensionRegistry.
 *
 * <p>Guards the reflection/PB_FILE descriptor parse path: without the registry,
 * google.api.http method options survive serialization only as unknown fields, and
 * every REST binding derived from them is silently dropped.
 */
class GatewayExtensionRegistryTest {

    private static byte[] descriptorWithHttpRule() {
        return FileDescriptorProto.newBuilder()
                .setName("demo.proto")
                .setPackage("demo")
                .setSyntax("proto3")
                .addDependency("google/api/annotations.proto")
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("DemoService")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("GetThing")
                                .setInputType(".demo.Req")
                                .setOutputType(".demo.Res")
                                .setOptions(MethodOptions.newBuilder()
                                        .setExtension(
                                                AnnotationsProto.http,
                                                HttpRule.newBuilder()
                                                        .setGet("/v1/things/{id}")
                                                        .build()))))
                .build()
                .toByteArray();
    }

    @Test
    @DisplayName(
            "GIVEN wire bytes with a google.api.http option WHEN parsed with the gateway registry SHOULD retain the HTTP rule")
    void GIVEN_httpAnnotatedDescriptorBytes_WHEN_parsedWithRegistry_SHOULD_retainHttpRule() throws Exception {
        FileDescriptorProto parsed =
                FileDescriptorProto.parseFrom(descriptorWithHttpRule(), GatewayExtensionRegistry.get());

        MethodOptions options = parsed.getService(0).getMethod(0).getOptions();
        assertThat(options.hasExtension(AnnotationsProto.http)).isTrue();
        assertThat(options.getExtension(AnnotationsProto.http).getGet()).isEqualTo("/v1/things/{id}");
    }

    @Test
    @DisplayName(
            "GIVEN the same bytes WHEN parsed without a registry SHOULD lose the HTTP rule (documents why the registry exists)")
    void GIVEN_httpAnnotatedDescriptorBytes_WHEN_parsedWithoutRegistry_SHOULD_loseHttpRule() throws Exception {
        FileDescriptorProto parsed = FileDescriptorProto.parseFrom(descriptorWithHttpRule());

        MethodOptions options = parsed.getService(0).getMethod(0).getOptions();
        assertThat(options.hasExtension(AnnotationsProto.http)).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN options of every shiguchi extension WHEN parsed with the gateway registry SHOULD retain graphql, method, merge, topic and field")
    void GIVEN_shiguchiApiOptions_WHEN_parsedWithRegistry_SHOULD_retainEveryExtension() throws Exception {
        MethodOptions methodOptions = MethodOptions.newBuilder()
                .setExtension(
                        GraphQlProto.graphql,
                        GraphQlRule.newBuilder().setName("g").build())
                .setExtension(
                        MethodProto.method,
                        MethodRule.newBuilder().addAuthScopes("s").build())
                .setExtension(
                        MergeProto.merge,
                        MergeRule.newBuilder().setTieBreakField("id").build())
                .setExtension(
                        TopicProto.topic,
                        UpstreamTopic.newBuilder().setPattern("a:{b}").build())
                .build();
        FieldOptions fieldOptions = FieldOptions.newBuilder()
                .setExtension(
                        GraphQlProto.field, FieldRule.newBuilder().setId(true).build())
                .build();

        MethodOptions parsedMethod =
                MethodOptions.parseFrom(methodOptions.toByteArray(), GatewayExtensionRegistry.get());
        FieldOptions parsedField = FieldOptions.parseFrom(fieldOptions.toByteArray(), GatewayExtensionRegistry.get());

        assertThat(parsedMethod.getExtension(GraphQlProto.graphql).getName()).isEqualTo("g");
        assertThat(parsedMethod.getExtension(MethodProto.method).getAuthScopesList())
                .containsExactly("s");
        assertThat(parsedMethod.getExtension(MergeProto.merge).getTieBreakField())
                .isEqualTo("id");
        assertThat(parsedMethod.getExtension(TopicProto.topic).getPattern()).isEqualTo("a:{b}");
        assertThat(parsedField.getExtension(GraphQlProto.field).getId()).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN the shiguchi option extensions WHEN their descriptors are inspected SHOULD keep the numbers 50100-50103 and 50200 under the new full names")
    void GIVEN_shiguchiExtensions_WHEN_inspected_SHOULD_keepNumbersAndNames() {
        assertThat(GraphQlProto.graphql.getDescriptor().getNumber()).isEqualTo(50100);
        assertThat(GraphQlProto.graphql.getDescriptor().getFullName()).isEqualTo("shiguchi.graphql");
        assertThat(GraphQlProto.field.getDescriptor().getNumber()).isEqualTo(50101);
        assertThat(GraphQlProto.field.getDescriptor().getFullName()).isEqualTo("shiguchi.field");
        assertThat(MergeProto.merge.getDescriptor().getNumber()).isEqualTo(50102);
        assertThat(MergeProto.merge.getDescriptor().getFullName()).isEqualTo("shiguchi.merge");
        assertThat(MethodProto.method.getDescriptor().getNumber()).isEqualTo(50103);
        assertThat(MethodProto.method.getDescriptor().getFullName()).isEqualTo("shiguchi.method");
        assertThat(TopicProto.topic.getDescriptor().getNumber()).isEqualTo(50200);
        assertThat(TopicProto.topic.getDescriptor().getFullName()).isEqualTo("shiguchi.topic");
    }

    @Test
    @DisplayName(
            "GIVEN a descriptor compiled against the pre-0.20 shiguchi.api package WHEN parsed with the gateway registry SHOULD decode the option by number")
    void GIVEN_descriptorFromOldPackage_WHEN_parsedWithRegistry_SHOULD_decodeByNumber() throws Exception {
        // An upstream built with 0.19 declares the option under its old full name; only the
        // extension number and the payload travel on the wire, so the option must still decode.
        MethodRule rule = MethodRule.newBuilder().addAuthScopes("orders:read").build();
        MethodOptions legacyOptions = MethodOptions.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(
                                50103,
                                UnknownFieldSet.Field.newBuilder()
                                        .addLengthDelimited(rule.toByteString())
                                        .build())
                        .build())
                .build();
        byte[] bytes = FileDescriptorProto.newBuilder()
                .setName("legacy.proto")
                .setPackage("legacy")
                .setSyntax("proto3")
                .addDependency("shiguchi/api/method.proto")
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("OrderService")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("GetOrder")
                                .setInputType(".legacy.Req")
                                .setOutputType(".legacy.Res")
                                .setOptions(legacyOptions)))
                .build()
                .toByteArray();

        FileDescriptorProto parsed = FileDescriptorProto.parseFrom(bytes, GatewayExtensionRegistry.get());

        MethodOptions options = parsed.getService(0).getMethod(0).getOptions();
        assertThat(options.hasExtension(MethodProto.method)).isTrue();
        assertThat(options.getExtension(MethodProto.method).getAuthScopesList()).containsExactly("orders:read");
    }
}
