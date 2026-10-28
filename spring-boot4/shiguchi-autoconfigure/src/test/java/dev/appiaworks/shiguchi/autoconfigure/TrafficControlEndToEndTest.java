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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Moving traffic between two running versions at runtime through the whole Boot 4 gateway: the
 * {@code gateway-traffic} endpoint reads the split and what each version is doing, changes the
 * split without a restart, and shows a version draining while a call to it is still open.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "management.endpoints.web.exposure.include=gateway-traffic,gateway-routes"
        })
@DisplayName("Traffic moved between versions at runtime end to end (Boot 4)")
class TrafficControlEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        /** Accepts the token {@code ops}: changing the split needs an authenticated caller. */
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
    /** Released to let a held call to the old version answer. */
    private static volatile CountDownLatch hold = new CountDownLatch(0);

    private static final Server OLD = startBackend("1.4.0");
    private static final Server NEW = startBackend("1.10.0");

    @DynamicPropertySource
    static void backends(DynamicPropertyRegistry registry) {
        String simple = "spring.cloud.discovery.client.simple.instances.catalog";
        registry.add(simple + "[0].uri", () -> "http://localhost:" + OLD.getPort());
        registry.add(simple + "[0].metadata.version", () -> "1.4.0");
        registry.add(simple + "[1].uri", () -> "http://localhost:" + NEW.getPort());
        registry.add(simple + "[1].metadata.version", () -> "1.10.0");

        registry.add("shiguchi.services[0].name", () -> "catalog");
        registry.add("shiguchi.services[0].discovery-name", () -> "catalog");
        registry.add("shiguchi.services[0].path-prefix", () -> "/catalog");
        registry.add("shiguchi.services[0].versions.traffic[0].version", () -> "1.4.0");
        registry.add("shiguchi.services[0].versions.traffic[0].weight", () -> "100");
        registry.add("shiguchi.services[0].versions.traffic[1].version", () -> "1.10.0");
        registry.add("shiguchi.services[0].versions.traffic[1].weight", () -> "0");

        // Not routed by version: the endpoint has nothing to change on it.
        registry.add("shiguchi.services[1].name", () -> "plain");
        registry.add("shiguchi.services[1].host", () -> "localhost");
        registry.add("shiguchi.services[1].port", () -> String.valueOf(OLD.getPort()));
        registry.add("shiguchi.services[1].path-prefix", () -> "/plain");
    }

    @AfterAll
    static void stop() {
        hold.countDown();
        OLD.shutdownNow();
        NEW.shutdownNow();
    }

    private WebTestClient client;
    private String base;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClientAndReset() {
        base = "http://localhost:" + environment.getRequiredProperty("local.server.port");
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl(base)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ops")
                .build();
        client.delete()
                .uri("/actuator/gateway-traffic/catalog")
                .exchange()
                .expectStatus()
                .isOk();
    }

    /** The version that answered: the backends echo their own version as the book id. */
    private String versionAnswering(String path) {
        byte[] body = client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        String json = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        return json.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private Set<String> versionsAnswering(int calls) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < calls; i++) seen.add(versionAnswering("/catalog/v1/books/x"));
        return seen;
    }

    private Map<String, Object> traffic() {
        return client.get()
                .uri("/actuator/gateway-traffic/catalog")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult()
                .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> version(Map<String, Object> traffic, String version) {
        return ((List<Map<String, Object>>) traffic.get("versions"))
                .stream()
                        .filter(row -> version.equals(row.get("version")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no row for " + version + " in " + traffic));
    }

    private WebTestClient.ResponseSpec post(String service, String weights) {
        return client.post()
                .uri("/actuator/gateway-traffic/" + service)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("weights", weights))
                .exchange();
    }

    /** Reads the endpoint until {@code condition} holds, for at most ten seconds. */
    private Map<String, Object> awaitTraffic(Predicate<Map<String, Object>> condition) throws InterruptedException {
        Map<String, Object> last = null;
        for (int i = 0; i < 100; i++) {
            last = traffic();
            if (condition.test(last)) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("condition never held; last read " + last);
    }

    @Test
    @DisplayName(
            "GIVEN the configured split WHEN the endpoint is read SHOULD show each version's weight, instances and state, the unweighted one safe to stop")
    void given_configuredSplit_when_read_should_describeVersions() {
        assertThat(versionsAnswering(10)).containsExactly("1.4.0");

        Map<String, Object> traffic = traffic();

        assertThat(traffic.get("weights")).isEqualTo(Map.of("1.4.0", 100, "1.10.0", 0));
        assertThat(traffic.get("overridden")).isEqualTo(false);
        assertThat(version(traffic, "1.4.0"))
                .containsEntry("weight", 100)
                .containsEntry("instances", 1)
                .containsEntry("state", "SERVING")
                .containsEntry("inFlight", 0);
        assertThat(((Number) version(traffic, "1.4.0").get("calls")).longValue())
                .isGreaterThanOrEqualTo(10);
        assertThat(version(traffic, "1.10.0")).containsEntry("state", "DRAINED");
        assertThat(traffic.get("safeToStop")).isEqualTo(List.of("1.10.0"));
    }

    @Test
    @DisplayName(
            "GIVEN calls on the old version WHEN new weights are posted and later deleted SHOULD move calls at once and then back, without a restart")
    void given_oldVersionServing_when_weightsPostedThenDeleted_should_moveCallsAndBack() {
        assertThat(versionsAnswering(10)).containsExactly("1.4.0");

        post("catalog", "1.4.0=0, 1.10.0=100")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.overridden")
                .isEqualTo(true)
                .jsonPath("$.configuredWeights['1.4.0']")
                .isEqualTo(100);

        assertThat(versionsAnswering(20)).containsExactly("1.10.0");
        Map<String, Object> moved = traffic();
        assertThat(version(moved, "1.4.0")).containsEntry("state", "DRAINED");
        assertThat(version(moved, "1.10.0")).containsEntry("state", "SERVING");
        assertThat(moved.get("safeToStop")).isEqualTo(List.of("1.4.0"));
        client.get()
                .uri("/actuator/gateway-routes/catalog")
                .exchange()
                .expectBody()
                .jsonPath("$.traffic['1.10.0']")
                .isEqualTo(100)
                .jsonPath("$.trafficOverridden")
                .isEqualTo(true);

        client.delete()
                .uri("/actuator/gateway-traffic/catalog")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.overridden")
                .isEqualTo(false);
        assertThat(versionsAnswering(10)).containsExactly("1.4.0");
    }

    @Test
    @DisplayName(
            "GIVEN a call held open on the old version WHEN its weight drops to 0 SHOULD report it draining until the call ends, then drained")
    void given_callInFlight_when_weightDropped_should_reportDrainingThenDrained() throws Exception {
        hold = new CountDownLatch(1);
        try {
            drain();
        } finally {
            hold.countDown();
        }
    }

    private void drain() throws Exception {
        CompletableFuture<String> held = WebClient.create(base)
                .get()
                .uri("/catalog/v1/hold/x")
                .retrieve()
                .bodyToMono(String.class)
                .toFuture();
        awaitTraffic(t -> ((Number) version(t, "1.4.0").get("inFlight")).longValue() == 1);

        post("catalog", "1.10.0=100").expectStatus().isOk();

        Map<String, Object> draining = traffic();
        assertThat(version(draining, "1.4.0"))
                .containsEntry("weight", 0)
                .containsEntry("state", "DRAINING")
                .containsEntry("inFlight", 1);
        assertThat(draining.get("safeToStop")).isEqualTo(List.of());
        // New calls already go to the new version while the old one drains.
        assertThat(versionsAnswering(5)).containsExactly("1.10.0");

        hold.countDown();
        assertThat(held.get(30, TimeUnit.SECONDS)).contains("1.4.0");
        Map<String, Object> drained =
                awaitTraffic(t -> "DRAINED".equals(version(t, "1.4.0").get("state")));
        assertThat(drained.get("safeToStop")).isEqualTo(List.of("1.4.0"));
    }

    @Test
    @DisplayName(
            "GIVEN invalid weights or a service not routed by version WHEN posted SHOULD answer 400 and leave the split unchanged")
    void given_invalidRequests_when_posted_should_reject() {
        post("catalog", "1.4.0=-1,1.10.0=100").expectStatus().isBadRequest();
        post("catalog", "1.4.0=0,1.10.0=0").expectStatus().isBadRequest();
        post("catalog", "1.4.0=lots").expectStatus().isBadRequest();
        post("catalog", "1.4.0").expectStatus().isBadRequest();
        post("plain", "1.4.0=100").expectStatus().isBadRequest();

        assertThat(traffic().get("overridden")).isEqualTo(false);
        assertThat(versionsAnswering(5)).containsExactly("1.4.0");
        client.get()
                .uri("/actuator/gateway-traffic/plain")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName(
            "GIVEN a caller without credentials WHEN it reads and then tries to change the split SHOULD be allowed to read but get 401 on writes")
    void given_anonymousCaller_when_changingSplit_should_beRejected() {
        WebTestClient anonymous = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl(base)
                .build();

        anonymous
                .get()
                .uri("/actuator/gateway-traffic/catalog")
                .exchange()
                .expectStatus()
                .isOk();
        anonymous
                .post()
                .uri("/actuator/gateway-traffic/catalog")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("weights", "1.10.0=100"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        anonymous
                .delete()
                .uri("/actuator/gateway-traffic/catalog")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        // The transcoded routes stay open, as PERMIT_ALL has them.
        anonymous.get().uri("/catalog/v1/books/x").exchange().expectStatus().isOk();

        assertThat(traffic().get("overridden")).isEqualTo(false);
    }

    // ---------------------------------------------------------------- the backend versions

    /** lib.Books.Get (GET /v1/books/{id}) and Hold (GET /v1/hold/{id}, answers once released). */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/traffic_control_books.proto")
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
                            .addMethod(method("Get", "/v1/books/{id}"))
                            .addMethod(method("Hold", "/v1/hold/{id}")))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, String path) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib.Book")
                .setOutputType(".lib.Book")
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                AnnotationsProto.http,
                                HttpRule.newBuilder().setGet(path).build()));
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

    /** A real gRPC server with reflection whose methods answer with {@code version} as the id. */
    private static Server startBackend(String version) {
        Descriptor book = FILE.findMessageTypeByName("Book");
        ServiceDescriptor service = FILE.findServiceByName("Books");
        var get = unary("Get", book);
        var holdMethod = unary("Hold", book);
        var descriptor = io.grpc.ServiceDescriptor.newBuilder("lib.Books")
                .setSchemaDescriptor(new Schema(FILE, service))
                .addMethod(get)
                .addMethod(holdMethod)
                .build();
        DynamicMessage answer = DynamicMessage.newBuilder(book)
                .setField(book.findFieldByName("id"), version)
                .build();
        ServerServiceDefinition definition = ServerServiceDefinition.builder(descriptor)
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(answer);
                    observer.onCompleted();
                }))
                .addMethod(holdMethod, ServerCalls.asyncUnaryCall((request, observer) -> {
                    CountDownLatch latch = hold;
                    CompletableFuture.runAsync(() -> {
                        try {
                            latch.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        observer.onNext(answer);
                        observer.onCompleted();
                    });
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

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> unary(String name, Descriptor book) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Books/" + name)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(book))
                .build();
    }
}
