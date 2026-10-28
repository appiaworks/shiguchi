package dev.appiaworks.shiguchi.reflection;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlFieldRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.proto.api.MergeProto;
import dev.appiaworks.shiguchi.proto.api.MergeRule;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.MethodRule;
import dev.appiaworks.shiguchi.proto.api.TopicProto;
import dev.appiaworks.shiguchi.proto.api.UpstreamTopic;
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
            "GIVEN options of every shiguchi.api extension WHEN parsed with the gateway registry SHOULD retain graphql, method, merge, topic and graphql_field")
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
                        GraphQlProto.graphqlField,
                        GraphQlFieldRule.newBuilder().setId(true).build())
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
        assertThat(parsedField.getExtension(GraphQlProto.graphqlField).getId()).isTrue();
    }
}
