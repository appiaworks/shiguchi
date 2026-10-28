package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Automated progressive rollouts through the whole Boot 3 gateway. Two services share the old
 * version {@code 1.4.0}; each has a {@code 1.10.0} registered next to it — a healthy one, which
 * the gateway steps up to every call, and one that fails every call, which it rolls back.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "management.endpoints.web.exposure.include=gateway-traffic"
        })
@DisplayName("Automated progressive rollout end to end (Boot 3)")
class VersionedRolloutEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        /** Accepts the token {@code ops}: rollout actions need an authenticated caller. */
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return token -> "ops".equals(token)
                    ? Mono.just(Jwt.withTokenValue(token)
                            .header("alg", "none")
                            .subject("ops")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(3600))
                            .build())
                    : Mono.error(new BadJwtException("unknown token"));
        }
    }

    private static final FileDescriptor FILE = buildFile();
    private static final Server OLD = startBackend("1.4.0", false);
    private static final Server GOOD = startBackend("1.10.0", false);
    private static final Server BAD = startBackend("1.10.0", true);

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        register(registry, "catalog-good", GOOD);
        register(registry, "catalog-bad", BAD);
        service(registry, 0, "good");
        service(registry, 1, "bad");
    }

    private static void register(DynamicPropertyRegistry registry, String id, Server newer) {
        String simple = "spring.cloud.discovery.client.simple.instances." + id;
        registry.add(simple + "[0].uri", () -> "http://localhost:" + OLD.getPort());
        registry.add(simple + "[0].metadata.version", () -> "1.4.0");
        registry.add(simple + "[1].uri", () -> "http://localhost:" + newer.getPort());
        registry.add(simple + "[1].metadata.version", () -> "1.10.0");
    }

    private static void service(DynamicPropertyRegistry registry, int i, String name) {
        String s = "shiguchi.services[" + i + "]";
        registry.add(s + ".name", () -> name);
        registry.add(s + ".discovery-name", () -> "catalog-" + name);
        registry.add(s + ".path-prefix", () -> "/" + name);
        // The old version takes every call until the rollout moves them.
        registry.add(s + ".versions.traffic[0].version", () -> "1.4.0");
        registry.add(s + ".versions.traffic[0].weight", () -> "100");
        registry.add(s + ".versions.rollout.enabled", () -> "true");
        registry.add(s + ".versions.rollout.steps", () -> "50,100");
        registry.add(s + ".versions.rollout.interval", () -> "1s");
        registry.add(s + ".versions.rollout.min-calls", () -> "10");
        registry.add(s + ".versions.rollout.max-error-rate", () -> "0.05");
    }

    @AfterAll
    static void stop() {
        OLD.shutdownNow();
        GOOD.shutdownNow();
        BAD.shutdownNow();
    }

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ops")
                .build();
    }

    /** The version that answered, or {@code "error"} for a failed call. */
    private String call(String service) {
        var result = client.get()
                .uri("/" + service + "/v1/books/x")
                .exchange()
                .expectBody()
                .returnResult();
        if (!result.getStatus().is2xxSuccessful()) return "error";
        String json = new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        return json.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private Map<String, Object> traffic(String service) {
        return client.get()
                .uri("/actuator/gateway-traffic/" + service)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult()
                .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rollout(Map<String, Object> traffic) {
        return (Map<String, Object>) traffic.get("rollout");
    }

    /** Calls {@code service} until its rollout reaches {@code condition}, for at most 60 seconds. */
    private Map<String, Object> callUntil(String service, Predicate<Map<String, Object>> condition)
            throws InterruptedException {
        Map<String, Object> last = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            for (int i = 0; i < 10; i++) call(service);
            last = traffic(service);
            if (condition.test(last)) return last;
            Thread.sleep(50);
        }
        throw new AssertionError("rollout never got there; last read " + last);
    }

    @Test
    @DisplayName(
            "GIVEN a healthy new version registered next to the old one WHEN calls flow SHOULD step it up to every call and leave the old one drained")
    void given_healthyNewVersion_when_callsFlow_should_promoteIt() throws Exception {
        Map<String, Object> done = callUntil(
                "good",
                t -> "WATCHING".equals(rollout(t).get("phase"))
                        && String.valueOf(rollout(t).get("lastDecision")).startsWith("promoted 1.10.0"));

        assertThat(done.get("weights")).isEqualTo(Map.of("1.4.0", 0, "1.10.0", 100));
        Set<String> answering = new HashSet<>();
        for (int i = 0; i < 20; i++) answering.add(call("good"));
        assertThat(answering).containsExactly("1.10.0");
        assertThat(traffic("good").get("safeToStop")).isEqualTo(List.of("1.4.0"));
    }

    @Test
    @DisplayName(
            "GIVEN a new version that fails its calls WHEN calls flow SHOULD roll it back to the old version and keep it there until resumed")
    void given_failingNewVersion_when_callsFlow_should_rollBack() throws Exception {
        Map<String, Object> rolledBack =
                callUntil("bad", t -> "ROLLED_BACK".equals(rollout(t).get("phase")));

        assertThat(String.valueOf(rollout(rolledBack).get("lastDecision")))
                .startsWith("rolled back 1.10.0 at 50 %: error rate");
        assertThat(rollout(rolledBack).get("failedVersions")).isEqualTo(List.of("1.10.0"));
        Set<String> answering = new HashSet<>();
        for (int i = 0; i < 20; i++) answering.add(call("bad"));
        assertThat(answering).containsExactly("1.4.0");

        client.post()
                .uri("/actuator/gateway-traffic/bad/resume")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.rollout.phase")
                .isEqualTo("ROLLING");
        client.post()
                .uri("/actuator/gateway-traffic/bad/abort")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.rollout.phase")
                .isEqualTo("ROLLED_BACK");
        client.post()
                .uri("/actuator/gateway-traffic/bad/abort")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    // ---------------------------------------------------------------- the backend versions

    /** lib.Books.Get (GET /v1/books/{id}). */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/rollout_books.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Book")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Books")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Get")
                                    .setInputType(".lib.Book")
                                    .setOutputType(".lib.Book")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/books/{id}")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Schema(FileDescriptor file, ServiceDescriptor service)
            implements ProtoFileDescriptorSupplier, ProtoServiceDescriptorSupplier {
        @Override
        public FileDescriptor getFileDescriptor() {
            return file;
        }

        @Override
        public ServiceDescriptor getServiceDescriptor() {
            return service;
        }
    }

    /** A real gRPC server with reflection whose Get answers with {@code version}, or fails when {@code failing}. */
    private static Server startBackend(String version, boolean failing) {
        Descriptor book = FILE.findMessageTypeByName("Book");
        ServiceDescriptor service = FILE.findServiceByName("Books");
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Books/Get")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                .build();
        var descriptor = io.grpc.ServiceDescriptor.newBuilder("lib.Books")
                .setSchemaDescriptor(new Schema(FILE, service))
                .addMethod(get)
                .build();
        ServerServiceDefinition definition = ServerServiceDefinition.builder(descriptor)
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    if (failing) {
                        observer.onError(Status.UNAVAILABLE
                                .withDescription("version " + version + " is broken")
                                .asRuntimeException());
                        return;
                    }
                    observer.onNext(DynamicMessage.newBuilder(book)
                            .setField(book.findFieldByName("id"), version)
                            .build());
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0)
                    .addService(definition)
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
