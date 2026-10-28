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
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerTransportFilter;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Backend-side throughput and protection through the whole Boot 4 gateway:
 * {@code rest.coalesce-requests} turning a burst of identical GETs into one backend call,
 * {@code services[].channels} opening one connection per channel, and
 * {@code services[].max-concurrent-calls} turning away the call over the limit with 503.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rest.coalesce-requests=true"
        })
@DisplayName("Request coalescing, channel pools and concurrency limits end to end (Boot 4)")
class RestBackendPoolingEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor MSG = FILE.findMessageTypeByName("Msg");

    private static final AtomicInteger SLOW_CALLS = new AtomicInteger();

    /** Connections each backend accepted, by remote address. */
    private static final Set<SocketAddress> SHARED_CONNECTIONS = ConcurrentHashMap.newKeySet();

    private static final Set<SocketAddress> POOLED_CONNECTIONS = ConcurrentHashMap.newKeySet();

    private static final Server SHARED = startBackend(SHARED_CONNECTIONS);
    private static final Server POOLED = startBackend(POOLED_CONNECTIONS);
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        service(registry, 0, "open", SHARED, null);
        service(registry, 1, "limited", SHARED, "/limited");
        registry.add("shiguchi.services[1].max-concurrent-calls", () -> "1");
        service(registry, 2, "pooled", POOLED, "/pooled");
        registry.add("shiguchi.services[2].channels", () -> "3");
    }

    private static void service(DynamicPropertyRegistry registry, int i, String name, Server server, String prefix) {
        String p = "shiguchi.services[" + i + "].";
        registry.add(p + "name", () -> name);
        registry.add(p + "mode", () -> "PB_FILE");
        registry.add(p + "pb-descriptor-path", () -> PB_FILE.toString());
        registry.add(p + "host", () -> "localhost");
        registry.add(p + "port", server::getPort);
        if (prefix != null) registry.add(p + "path-prefix", () -> prefix);
    }

    @AfterAll
    static void stop() throws IOException {
        SHARED.shutdownNow();
        POOLED.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebClient web;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        web = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    private record Answer(int status, String cacheStatus) {}

    private Mono<Answer> get(String path) {
        return web.get()
                .uri(path)
                .exchangeToMono(r -> r.releaseBody()
                        .thenReturn(new Answer(
                                r.statusCode().value(),
                                r.headers().asHttpHeaders().getFirst("Cache-Status"))));
    }

    @Test
    @DisplayName(
            "GIVEN coalesce-requests WHEN four identical GETs arrive together SHOULD make one backend call and answer all four")
    void given_coalescing_when_identicalGetsTogether_should_callBackendOnce() {
        int before = SLOW_CALLS.get();

        List<Answer> answers = Flux.range(0, 4)
                .flatMap(i -> get("/v1/slow/same"))
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(answers).extracting(Answer::status).containsOnly(200);
        assertThat(answers).extracting(Answer::cacheStatus).contains("shiguchi; fwd=miss; collapsed");
        assertThat(SLOW_CALLS.get() - before).isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN coalesce-requests WHEN GETs differ in their arguments SHOULD give each its own backend call")
    void given_coalescing_when_differentArguments_should_callBackendPerRequest() {
        int before = SLOW_CALLS.get();

        List<Answer> answers = Flux.just("a", "b", "c")
                .flatMap(id -> get("/v1/slow/" + id))
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(answers).extracting(Answer::status).containsOnly(200);
        assertThat(SLOW_CALLS.get() - before).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "GIVEN max-concurrent-calls 1 WHEN two different calls overlap SHOULD answer one and turn the other away with 503")
    void given_concurrencyLimit_when_callsOverlap_should_reject503() {
        List<Integer> statuses = Flux.just("x", "y")
                .flatMap(id -> get("/limited/v1/slow/" + id))
                .map(Answer::status)
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(statuses).containsExactlyInAnyOrder(200, 503);

        // The permit comes back when the call ends: the next call goes through.
        assertThat(get("/limited/v1/slow/z").block(Duration.ofSeconds(30)).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("GIVEN channels 3 WHEN several calls are made SHOULD spread them over three connections")
    void given_threeChannels_when_calls_should_useThreeConnections() {
        for (int i = 0; i < 6; i++) {
            assertThat(get("/pooled/v1/fast/" + i).block(Duration.ofSeconds(30)).status())
                    .isEqualTo(200);
        }

        assertThat(POOLED_CONNECTIONS).hasSize(3);
    }

    // ---------------------------------------------------------------- the gRPC backends

    /** lib.Echo: Slow (GET /v1/slow/{text}, answers after 400 ms) and Fast (GET /v1/fast/{text}). */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/pooling.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Msg")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("text")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Echo")
                            .addMethod(method("Slow", "/v1/slow/{text}"))
                            .addMethod(method("Fast", "/v1/fast/{text}")))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodDescriptorProto.Builder method(String name, String path) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib.Msg")
                .setOutputType(".lib.Msg")
                .setOptions(MethodOptions.newBuilder()
                        .setExtension(
                                AnnotationsProto.http,
                                HttpRule.newBuilder().setGet(path).build()));
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-pooling", ".pb");
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

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> descriptor(String name) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Echo/" + name)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(MSG))
                .build();
    }

    private static Server startBackend(Set<SocketAddress> connections) {
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Echo")
                .addMethod(descriptor("Slow"), ServerCalls.asyncUnaryCall((request, observer) -> {
                    SLOW_CALLS.incrementAndGet();
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .addMethod(descriptor("Fast"), ServerCalls.asyncUnaryCall((request, observer) -> {
                    observer.onNext(request);
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0)
                    .addService(service)
                    .addTransportFilter(new ServerTransportFilter() {
                        @Override
                        public Attributes transportReady(Attributes attributes) {
                            SocketAddress remote = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
                            if (remote != null) connections.add(remote);
                            return attributes;
                        }
                    })
                    .build()
                    .start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
