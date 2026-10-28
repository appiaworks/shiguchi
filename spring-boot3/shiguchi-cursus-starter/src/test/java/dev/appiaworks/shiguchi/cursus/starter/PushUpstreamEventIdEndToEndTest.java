package dev.appiaworks.shiguchi.cursus.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.appiaworks.shiguchi.proto.api.TopicProto;
import dev.appiaworks.shiguchi.proto.api.UpstreamTopic;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

/**
 * {@code event_id_field} on the Boot 3 gateway: {@code ev.Ticks/Watch} streams {@code Tick{seq}}
 * and names {@code seq} as its event id, so the SSE frames of {@code ticks:a} carry
 * {@code id: <seq>} (zero-padded by the memory store) and a client reconnecting with that
 * {@code Last-Event-ID} receives only later ticks.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.cursus.enabled=true",
            "shiguchi.cursus.namespaces.ticks.qos=AT_LEAST_ONCE",
            "shiguchi.cursus.namespaces.ticks.history-size=500",
            "shiguchi.cursus.upstream.idle-linger=300ms",
            "shiguchi.cursus.upstream.lease-ttl=2s",
            "shiguchi.cursus.upstream.retry-backoff=100ms",
            "shiguchi.cursus.connection.keepalive=200ms",
            "shiguchi.cursus.connection.max-lifetime=0"
        })
@DisplayName("Push upstream event_id_field end to end (Boot 3)")
class PushUpstreamEventIdEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};
    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor TICK = FILE.findMessageTypeByName("Tick");
    private static final Descriptor REQUEST = FILE.findMessageTypeByName("WatchRequest");
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor();
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "ev");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        TICKER.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    @Autowired
    private Environment environment;

    private WebClient client;

    @BeforeEach
    void client() {
        client = WebClient.create("http://localhost:" + environment.getRequiredProperty("local.server.port"));
    }

    @Test
    @DisplayName(
            "GIVEN a topic with event_id_field seq WHEN subscribed over SSE SHOULD carry id: equal to seq, and a reconnect with Last-Event-ID receive only later ticks")
    void given_eventIdField_when_subscribedAndResumed_should_carryUpstreamSequenceAsId() {
        List<ServerSentEvent<String>> first = new CopyOnWriteArrayList<>();
        Disposable stream = subscribe(null, first);
        String resumeFrom;
        long resumeSeq;
        try {
            awaitTrue(() -> first.size() >= 3);
            for (ServerSentEvent<String> frame : first) {
                long seq = parse(frame.data()).get("seq").asLong();
                assertThat(Long.parseLong(frame.id())).as("id: line equals seq").isEqualTo(seq);
            }
            ServerSentEvent<String> second = first.get(1);
            resumeFrom = second.id();
            resumeSeq = parse(second.data()).get("seq").asLong();
        } finally {
            stream.dispose();
        }

        List<ServerSentEvent<String>> resumed = new CopyOnWriteArrayList<>();
        Disposable again = subscribe(resumeFrom, resumed);
        try {
            awaitTrue(() -> resumed.size() >= 2);
            assertThat(resumed)
                    .allSatisfy(frame ->
                            assertThat(parse(frame.data()).get("seq").asLong()).isGreaterThan(resumeSeq));
            assertThat(parse(resumed.get(0).data()).get("seq").asLong()).isEqualTo(resumeSeq + 1);
        } finally {
            again.dispose();
        }
    }

    // ---- helpers ----

    private Disposable subscribe(String lastEventId, List<ServerSentEvent<String>> frames) {
        return client.get()
                .uri("/push/subscribe?topic=ticks:a")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(h -> {
                    if (lastEventId != null) h.set("Last-Event-ID", lastEventId);
                })
                .retrieve()
                .bodyToFlux(SSE)
                .filter(e -> e.event() != null && !"close".equals(e.event()))
                .subscribe(frames::add);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(json, e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in 30s");
            Thread.onSpinWait();
        }
    }

    // ---- the backend: ev.Ticks/Watch(WatchRequest{name}) returns (stream Tick{name, seq int64}) ----

    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ev/ticks.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addDependency("shiguchi/api/topic.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("WatchRequest")
                            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Tick")
                            .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                            .addField(field("seq", 2, FieldDescriptorProto.Type.TYPE_INT64)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Ticks")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Watch")
                                    .setInputType(".ev.WatchRequest")
                                    .setOutputType(".ev.Tick")
                                    .setServerStreaming(true)
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    TopicProto.topic,
                                                    UpstreamTopic.newBuilder()
                                                            .setPattern("ticks:{name}")
                                                            .setEventIdField("seq")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {TopicProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("ev-ticks-upstream", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(TopicProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Emits Tick{seq = 1, 2, 3, ...} every 100 ms for the requested name until the call is cancelled. */
    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> watch =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.SERVER_STREAMING)
                        .setFullMethodName("ev.Ticks/Watch")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(REQUEST))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(TICK))
                        .build();
        AtomicLong next = new AtomicLong();
        ServerServiceDefinition service = ServerServiceDefinition.builder("ev.Ticks")
                .addMethod(watch, ServerCalls.asyncServerStreamingCall((request, observer) -> {
                    String name = (String) request.getField(REQUEST.findFieldByName("name"));
                    ServerCallStreamObserver<DynamicMessage> call = (ServerCallStreamObserver<DynamicMessage>) observer;
                    ScheduledFuture<?> ticks = TICKER.scheduleAtFixedRate(
                            () -> {
                                if (call.isCancelled()) return;
                                try {
                                    call.onNext(DynamicMessage.newBuilder(TICK)
                                            .setField(TICK.findFieldByName("name"), name)
                                            .setField(TICK.findFieldByName("seq"), next.incrementAndGet())
                                            .build());
                                } catch (RuntimeException ignored) {
                                    // the call ended under us
                                }
                            },
                            0,
                            100,
                            TimeUnit.MILLISECONDS);
                    call.setOnCancelHandler(() -> ticks.cancel(false));
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
