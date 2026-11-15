package dev.appiaworks.shiguchi.openapi.starter;

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
import dev.appiaworks.shiguchi.proto.MethodProto;
import dev.appiaworks.shiguchi.proto.MethodRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * With {@code shiguchi.exposure.expose-tags}, a method without a listed tag must be absent from the
 * generated OpenAPI document (paths, operation ids and schemas) and answer 404 when called. No
 * backend is needed for the document, and a hidden method is never dispatched.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.graphql.enabled=false",
            "shiguchi.ui.enabled=false",
            "shiguchi.rest.auto-mapping=true",
            "shiguchi.exposure.expose-tags[0]=external"
        })
@DisplayName("Exposure by tag in the OpenAPI document end to end (Boot 4, no leakage)")
class OpenApiExposureLeakageEndToEndTest {

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

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    private String document() {
        return client.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    @DisplayName(
            "GIVEN expose-tags=external WHEN the OpenAPI document is fetched SHOULD describe the exposed method and none of the hidden method's path, operation or schema names")
    void given_exposeTags_when_documentFetched_should_notLeakHiddenMethod() {
        String doc = document();

        assertThat(doc).contains("GetPublic").contains("PublicThing");
        assertThat(doc)
                .doesNotContain("GetSecret")
                .doesNotContain("SecretThing")
                .doesNotContain("SecretRequest")
                .doesNotContain("classified");
    }

    @Test
    @DisplayName("GIVEN a hidden method WHEN it is called directly SHOULD answer 404 like a method that does not exist")
    void given_hiddenMethod_when_calledDirectly_should_answerNotFound() {
        client.post()
                .uri("/expo.Store/GetSecret")
                .header("Content-Type", "application/json")
                .bodyValue("{\"id\":\"1\"}")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    // ------------------------------------------------------------------ fixtures

    private static FieldDescriptorProto.Builder field(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static MethodDescriptorProto.Builder method(String name, String in, String out, String tag) {
        MethodDescriptorProto.Builder method = MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".expo." + in)
                .setOutputType(".expo." + out);
        if (tag != null) {
            method.setOptions(MethodOptions.newBuilder()
                    .setExtension(
                            MethodProto.method,
                            MethodRule.newBuilder().addTags(tag).build()));
        }
        return method;
    }

    /** GetPublic carries the tag external; GetSecret has no tag, so it is hidden. */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("expo/store.proto")
                    .setSyntax("proto3")
                    .setPackage("expo")
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
                            .addMethod(method("GetPublic", "PublicRequest", "PublicThing", "external"))
                            .addMethod(method("GetSecret", "SecretRequest", "SecretThing", null)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {MethodProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeDescriptorSet(FileDescriptor file) {
        try {
            Path path = Files.createTempFile("expo-store", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
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
