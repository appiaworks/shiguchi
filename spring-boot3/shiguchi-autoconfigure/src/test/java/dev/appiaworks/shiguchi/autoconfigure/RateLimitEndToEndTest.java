package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Inbound rate limiting on the Boot 3 gateway with the MEMORY backend: a caller that exceeds the
 * burst gets 429 {@code RESOURCE_EXHAUSTED} with {@code Retry-After} and the rate-limit headers,
 * the backend never sees the refused request, a different caller (by {@code X-Forwarded-For})
 * is unaffected, and actuator is never limited.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rate-limit.enabled=true",
            "shiguchi.rate-limit.replenish-rate=1",
            "shiguchi.rate-limit.burst-capacity=2",
            "management.endpoints.web.exposure.include=health"
        })
@DisplayName("Inbound rate limiting end to end (Boot 3, MEMORY backend)")
class RateLimitEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET_REQUEST = FILE.findMessageTypeByName("GetRequest");
    private static final AtomicInteger BACKEND_CALLS = new AtomicInteger();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @org.springframework.test.context.DynamicPropertySource
    static void backend(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "store");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
        BACKEND_CALLS.set(0);
    }

    private record Reply(int status, HttpHeaders headers, String body) {}

    private Reply get(String uri, String forwardedFor) {
        return client.get()
                .uri(uri)
                .header("X-Forwarded-For", forwardedFor)
                .exchangeToMono(r -> r.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(b -> new Reply(r.statusCode().value(), r.headers().asHttpHeaders(), b)))
                .block(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN a burst of 2 WHEN one caller sends three requests SHOULD serve two with rate-limit headers and refuse the third with 429, Retry-After and no backend call; another caller is unaffected; actuator is never limited")
    void given_burst_when_exceeded_should_refuseThirdRequest() {
        Reply first = get("/v1/items/1", "203.0.113.10");
        Reply second = get("/v1/items/2", "203.0.113.10");
        Reply third = get("/v1/items/3", "203.0.113.10");

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.headers().getFirst("X-RateLimit-Remaining")).isEqualTo("1");
        assertThat(first.headers().getFirst("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.headers().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(third.status()).isEqualTo(429);
        assertThat(third.headers().getFirst("Retry-After")).isEqualTo("1");
        assertThat(third.headers().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(third.body()).contains("\"code\":\"RESOURCE_EXHAUSTED\"").contains("Rate limit exceeded");
        assertThat(BACKEND_CALLS.get()).isEqualTo(2);

        Reply other = get("/v1/items/4", "203.0.113.11");
        assertThat(other.status()).isEqualTo(200);
        assertThat(BACKEND_CALLS.get()).isEqualTo(3);

        for (int i = 0; i < 4; i++) {
            Reply health = get("/actuator/health", "203.0.113.10");
            assertThat(health.status()).isEqualTo(200);
            assertThat(health.headers().getFirst("X-RateLimit-Limit")).isNull();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("rl/store.proto")
                    .setPackage("rl")
                    .setSyntax("proto3")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder().setName("Item").addField(field("id", 1)))
                    .addMessageType(
                            DescriptorProto.newBuilder().setName("GetRequest").addField(field("id", 1)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Store")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("GetItem")
                                    .setInputType(".rl.GetRequest")
                                    .setOutputType(".rl.Item")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/items/{id}")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("rl-store", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        var get = io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("rl.Store/GetItem")
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(GET_REQUEST))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("rl.Store")
                .addMethod(get, ServerCalls.asyncUnaryCall((request, observer) -> {
                    BACKEND_CALLS.incrementAndGet();
                    observer.onNext(DynamicMessage.newBuilder(ITEM)
                            .setField(ITEM.findFieldByName("id"), request.getField(GET_REQUEST.findFieldByName("id")))
                            .build());
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
