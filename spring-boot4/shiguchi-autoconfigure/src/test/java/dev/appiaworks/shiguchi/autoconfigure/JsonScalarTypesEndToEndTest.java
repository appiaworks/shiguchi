package dev.appiaworks.shiguchi.autoconfigure;

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
import com.google.protobuf.DurationProto;
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
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * ISO string encoding of google.type.Date, google.type.TimeOfDay, google.type.DateTime,
 * google.type.Decimal on REST JSON, plus ISO-8601 Duration text inbound, end to end through the
 * whole Boot 4 gateway against a real gRPC server.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"shiguchi.ui.enabled=false", "shiguchi.graphql.enabled=false", "shiguchi.openapi.enabled=false"})
@DisplayName("google.type scalar types as ISO strings end to end (Boot 4)")
class JsonScalarTypesEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor GOOGLE_TYPE_DATE = com.google.type.DateProto.getDescriptor();
    private static final FileDescriptor GOOGLE_TYPE_TIME_OF_DAY = com.google.type.TimeOfDayProto.getDescriptor();
    private static final FileDescriptor GOOGLE_TYPE_DATE_TIME = com.google.type.DateTimeProto.getDescriptor();
    private static final FileDescriptor GOOGLE_TYPE_DECIMAL = com.google.type.DecimalProto.getDescriptor();
    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor EVENT = FILE.findMessageTypeByName("Event");
    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "scalars");
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

    private WebTestClient client;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        client = WebTestClient.bindToServer()
                .responseTimeout(java.time.Duration.ofSeconds(30))
                .baseUrl("http://localhost:" + environment.getRequiredProperty("local.server.port"))
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN iso string scalars posted as json WHEN an Event with iso strings is sent SHOULD convert to proto fields and echo with iso strings in response")
    void given_isoStringScalars_when_posted_should_convertAndEchoWithIsoStrings() {
        client.post()
                .uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                        "{\"day\":\"2026-09-22\",\"opens\":\"09:30:00\",\"starts\":\"2026-09-22T14:03:11-04:00\",\"price\":\"12.34\",\"length\":\"PT1H30M\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.day")
                .isEqualTo("2026-09-22")
                .jsonPath("$.opens")
                .isEqualTo("09:30:00")
                .jsonPath("$.starts")
                .isEqualTo("2026-09-22T14:03:11-04:00")
                .jsonPath("$.price")
                .isEqualTo("12.34")
                .jsonPath("$.length")
                .isEqualTo("5400s")
                .jsonPath("$.summary")
                .value(s -> org.assertj.core.api.Assertions.assertThat((String) s)
                        .contains("2026/9/22", "opens=9:30", "length=5400"));
    }

    @Test
    @DisplayName(
            "GIVEN object form scalars posted as json WHEN an Event with object-form dates is sent SHOULD accept and echo with iso strings in response")
    void given_objectFormScalars_when_posted_should_acceptAndEchoWithIsoStrings() {
        client.post()
                .uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"day\":{\"year\":2026,\"month\":9,\"day\":22}}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.day")
                .isEqualTo("2026-09-22");
    }

    @Test
    @DisplayName(
            "GIVEN query parameters as iso strings WHEN a GET with query scalars is called SHOULD convert and echo them")
    void given_queryParameterScalars_when_getIsCalled_should_convertAndEchoThem() {
        client.get()
                .uri("/v1/events?day=2026-09-22")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.day")
                .isEqualTo("2026-09-22")
                .jsonPath("$.summary")
                .value(s ->
                        org.assertj.core.api.Assertions.assertThat((String) s).contains("2026/9/22"));
    }

    @Test
    @DisplayName(
            "GIVEN an invalid date posted as iso string WHEN malformed date is sent SHOULD respond with 400 INVALID_ARGUMENT")
    void given_invalidDate_when_posted_should_respond400() {
        client.post()
                .uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"day\":\"2026-02-30\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName(
            "GIVEN a partial date in iso string form WHEN a partial date is sent SHOULD accept and echo the partial form")
    void given_partialDate_when_posted_should_acceptAndEcho() {
        client.post()
                .uri("/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"day\":\"--12-25\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.day")
                .isEqualTo("--12-25");
    }

    // ---------------------------------------------------------------- the gRPC backend

    /**
     * scalars.Calendar: Echo(Event) at POST /v1/events body:*, Find(Event) at GET /v1/events. Event
     * message has Date, TimeOfDay, DateTime, Decimal, Duration, and a summary string that the
     * backend computes to prove field conversion.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("scalars/calendar.proto")
                    .setSyntax("proto3")
                    .setPackage("scalars")
                    .addDependency("google/api/annotations.proto")
                    .addDependency("google/type/date.proto")
                    .addDependency("google/type/timeofday.proto")
                    .addDependency("google/type/datetime.proto")
                    .addDependency("google/type/decimal.proto")
                    .addDependency("google/protobuf/duration.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Event")
                            .addField(message("day", 1, ".google.type.Date"))
                            .addField(message("opens", 2, ".google.type.TimeOfDay"))
                            .addField(message("starts", 3, ".google.type.DateTime"))
                            .addField(message("price", 4, ".google.type.Decimal"))
                            .addField(message("length", 5, ".google.protobuf.Duration"))
                            .addField(scalar("summary", 6)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Calendar")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Echo")
                                    .setInputType(".scalars.Event")
                                    .setOutputType(".scalars.Event")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setPost("/v1/events")
                                                            .setBody("*")
                                                            .build())))
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Find")
                                    .setInputType(".scalars.Event")
                                    .setOutputType(".scalars.Event")
                                    .setOptions(MethodOptions.newBuilder()
                                            .setExtension(
                                                    AnnotationsProto.http,
                                                    HttpRule.newBuilder()
                                                            .setGet("/v1/events")
                                                            .build()))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {
                AnnotationsProto.getDescriptor(),
                GOOGLE_TYPE_DATE,
                GOOGLE_TYPE_TIME_OF_DAY,
                GOOGLE_TYPE_DATE_TIME,
                GOOGLE_TYPE_DECIMAL,
                DurationProto.getDescriptor()
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static FieldDescriptorProto.Builder message(String name, int number, String type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("scalars-calendar", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(DurationProto.getDescriptor().toProto())
                    .addFile(GOOGLE_TYPE_DATE.toProto())
                    .addFile(GOOGLE_TYPE_TIME_OF_DAY.toProto())
                    .addFile(GOOGLE_TYPE_DATE_TIME.toProto())
                    .addFile(GOOGLE_TYPE_DECIMAL.toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Server startBackend() {
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> echo =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("scalars.Calendar/Echo")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(EVENT))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(EVENT))
                        .build();
        io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> find =
                io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(MethodType.UNARY)
                        .setFullMethodName("scalars.Calendar/Find")
                        .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(EVENT))
                        .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(EVENT))
                        .build();
        ServerServiceDefinition service = ServerServiceDefinition.builder("scalars.Calendar")
                .addMethod(echo, ServerCalls.asyncUnaryCall((request, observer) -> {
                    // Echo, with a summary computed from the proto fields the backend received.
                    DynamicMessage response = request.toBuilder()
                            .setField(EVENT.findFieldByName("summary"), computeSummary(request))
                            .build();
                    observer.onNext(response);
                    observer.onCompleted();
                }))
                .addMethod(find, ServerCalls.asyncUnaryCall((request, observer) -> {
                    DynamicMessage response = request.toBuilder()
                            .setField(EVENT.findFieldByName("summary"), computeSummary(request))
                            .build();
                    observer.onNext(response);
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compute summary from the event's date/time/duration fields to prove they arrived as proto. */
    private static String computeSummary(DynamicMessage event) {
        StringBuilder sb = new StringBuilder();

        // Date: year/month/day
        DynamicMessage day = (DynamicMessage) event.getField(EVENT.findFieldByName("day"));
        if (day != null) {
            Descriptor dayDesc = day.getDescriptorForType();
            int year = ((Number) day.getField(dayDesc.findFieldByName("year"))).intValue();
            int month = ((Number) day.getField(dayDesc.findFieldByName("month"))).intValue();
            int dayNum = ((Number) day.getField(dayDesc.findFieldByName("day"))).intValue();
            if (year > 0) sb.append(year).append("/");
            if (month > 0) sb.append(month).append("/");
            if (dayNum > 0) sb.append(dayNum).append(" ");
        }

        // TimeOfDay: hours:minutes
        DynamicMessage opens = (DynamicMessage) event.getField(EVENT.findFieldByName("opens"));
        if (opens != null) {
            Descriptor opensDesc = opens.getDescriptorForType();
            int hours = ((Number) opens.getField(opensDesc.findFieldByName("hours"))).intValue();
            int minutes = ((Number) opens.getField(opensDesc.findFieldByName("minutes"))).intValue();
            sb.append("opens=").append(hours).append(":").append(minutes).append(" ");
        }

        // Duration: seconds
        DynamicMessage length = (DynamicMessage) event.getField(EVENT.findFieldByName("length"));
        if (length != null) {
            Descriptor lengthDesc = length.getDescriptorForType();
            long seconds = ((Number) length.getField(lengthDesc.findFieldByName("seconds"))).longValue();
            sb.append("length=").append(seconds);
        }

        return sb.toString();
    }
}
