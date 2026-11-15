package dev.appiaworks.shiguchi.graphql.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import dev.appiaworks.shiguchi.proto.GraphQlRule.Op;
import dev.appiaworks.shiguchi.proto.MethodProto;
import dev.appiaworks.shiguchi.proto.MethodRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * With {@code shiguchi.exposure.expose-tags}, a method without a listed tag must not exist on the
 * GraphQL surface: its field, its message types and its names are absent from introspection, and
 * querying it fails validation as an unknown field. No backend is needed: nothing is dispatched.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.exposure.expose-tags[0]=external"
        })
@DisplayName("Exposure by tag on the GraphQL surface end to end (Boot 4, no leakage through introspection)")
class GraphQlExposureLeakageEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final Path PB_FILE = writeDescriptorSet(buildFile());

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "expo");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> 1);
    }

    @AfterAll
    static void stop() throws IOException {
        Files.deleteIfExists(PB_FILE);
    }

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private String graphql(String query) {
        return client.post()
                .uri("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("query", query))
                .exchangeToMono(r -> r.bodyToMono(String.class))
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN expose-tags=external WHEN the whole schema is introspected SHOULD list the exposed field and type and none of the hidden method's names or types")
    void given_exposeTags_when_schemaIntrospected_should_notLeakHiddenNames() {
        String body = graphql("{ __schema { queryType { fields { name } } types { name } } }")
                + graphql("{ __type(name: \"Query\") { fields { name args { name } } } }");

        assertThat(body).contains("\"publicItem\"").contains("PublicThing");
        assertThat(body)
                .doesNotContain("secretItem")
                .doesNotContain("SecretThing")
                .doesNotContain("SecretRequest")
                .doesNotContain("classified");
    }

    @Test
    @DisplayName("GIVEN a hidden method WHEN its type is looked up with __type SHOULD answer null")
    void given_hiddenMethod_when_typeLookedUp_should_answerNull() {
        String hidden = graphql("{ __type(name: \"SecretThing\") { name } }");
        String shown = graphql("{ __type(name: \"PublicThing\") { name } }");

        assertThat(hidden).contains("\"__type\":null");
        assertThat(shown).contains("PublicThing");
    }

    @Test
    @DisplayName("GIVEN a hidden method WHEN it is queried directly SHOULD fail validation and return no data")
    void given_hiddenMethod_when_queriedDirectly_should_failValidation() {
        String body = graphql("{ secretItem(id: \"1\") { id classified } }");

        assertThat(body).contains("\"errors\"");
        assertThat(body).doesNotContain("\"secretItem\":{");
    }

    // ------------------------------------------------------------------ fixtures

    private static FieldDescriptorProto.Builder field(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static MethodDescriptorProto.Builder query(
            String method, String graphqlName, String in, String out, String tag) {
        MethodOptions.Builder options = MethodOptions.newBuilder()
                .setExtension(
                        GraphQlProto.graphql,
                        GraphQlRule.newBuilder()
                                .setName(graphqlName)
                                .setType(Op.QUERY)
                                .build());
        if (tag != null) {
            options.setExtension(
                    MethodProto.method, MethodRule.newBuilder().addTags(tag).build());
        }
        return MethodDescriptorProto.newBuilder()
                .setName(method)
                .setInputType(".expo." + in)
                .setOutputType(".expo." + out)
                .setOptions(options);
    }

    /** PublicThing is exposed (tag external); SecretThing and its request have no tag, so they are hidden. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("expo/store.proto")
                    .setSyntax("proto3")
                    .setPackage("expo")
                    .addDependency("shiguchi/graphql.proto")
                    .addDependency(MethodProto.getDescriptor().getName())
                    .addMessageType(
                            DescriptorProto.newBuilder().setName("PublicThing").addField(field("id", 1)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("PublicRequest")
                            .addField(field("id", 1)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("SecretThing")
                            .addField(field("id", 1))
                            .addField(field("classified", 2)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("SecretRequest")
                            .addField(field("id", 1)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(query("GetPublic", "publicItem", "PublicRequest", "PublicThing", "external"))
                            .addMethod(query("GetSecret", "secretItem", "SecretRequest", "SecretThing", null)))
                    .build();
            return FileDescriptor.buildFrom(
                    proto, new FileDescriptor[] {GraphQlProto.getDescriptor(), MethodProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet(FileDescriptor file) {
        try {
            Path path = Files.createTempFile("expo-store", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(GraphQlProto.getDescriptor().toProto())
                    .addFile(MethodProto.getDescriptor().toProto())
                    .addFile(file.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
