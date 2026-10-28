package dev.appiaworks.protobuf.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.google.geo.type.Viewport;
import com.google.protobuf.Any;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import com.google.rpc.Status;
import com.google.type.Interval;
import com.google.type.LatLng;
import com.google.type.PostalAddress;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.JsonTrees;
import dev.appiaworks.protobuf.extras.serde.typed.TypedParity;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ProtoJson} has three ways to print — the wire transcoder, a compiled serializer and the
 * reflective converter — and picks per call: the transcoder for a generated message or bytes, a
 * compiled serializer for a DynamicMessage in the default layout, the converter otherwise. Whichever it picks, the JSON must be the same value.
 *
 * <p>Field order is the one thing allowed to differ (the transcoder follows the wire), so JSON is
 * compared as a tree unless a test says otherwise.
 */
class ProtoJsonTiersTest {

    @BeforeAll
    static void requiresCodegen() {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
    }

    private static final JsonFactory JSON = new JsonFactory();

    private static final Viewport VIEWPORT = Viewport.newBuilder()
            .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
            .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
            .build();

    /** Scalars at their defaults, a repeated string, nested and repeated messages, a map, durations. */
    private static final List<Message> MESSAGES = List.of(
            VIEWPORT,
            Interval.newBuilder()
                    .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
                    .build(),
            PostalAddress.newBuilder()
                    .setRegionCode("US")
                    .addAddressLines("1 Main St")
                    .addAddressLines("Suite 2")
                    .build(),
            PostalAddress.getDefaultInstance(),
            BadRequest.newBuilder()
                    .addFieldViolations(BadRequest.FieldViolation.newBuilder()
                            .setField("name")
                            .setDescription("must not be blank"))
                    .addFieldViolations(BadRequest.FieldViolation.newBuilder().setField("age"))
                    .build(),
            ErrorInfo.newBuilder()
                    .setReason("QUOTA")
                    .setDomain("example.com")
                    .putMetadata("limit", "10")
                    .putMetadata("unit", "rps")
                    .build(),
            RetryInfo.newBuilder()
                    .setRetryDelay(ProtoTypes.duration(Duration.ofMillis(1500)))
                    .build());

    private static List<ProtoJson> dialects(boolean compiled) {
        return List.of(
                ProtoJson.builder().compiledSerializers(compiled).build(),
                ProtoJson.builder()
                        .compiledSerializers(compiled)
                        .scalar(latLngCodec())
                        .int64AsNumber(true)
                        .build(),
                ProtoJson.builder()
                        .compiledSerializers(compiled)
                        .preserveProtoFieldNames(true)
                        .omitDefaultValues(true)
                        .enumsAsInts(true)
                        .build());
    }

    @Test
    @DisplayName(
            "Given the code generator on this JVM, when a ProtoJson is built, should compile serializers unless switched off")
    void given_generatorPresent_when_built_should_compileUnlessSwitchedOff() {
        assertThat(ProtoJson.defaults().usesCompiledSerializers()).isTrue();
        assertThat(ProtoJson.builder().compiledSerializers(false).build().usesCompiledSerializers())
                .isFalse();
    }

    @Test
    @DisplayName("Given every message and dialect, when printed through each path, should give the same JSON value")
    void given_everyMessageAndDialect_when_printedThroughEachPath_should_giveSameJson() throws Exception {
        List<ProtoJson> compiled = dialects(true);
        List<ProtoJson> reflective = dialects(false);

        for (int d = 0; d < compiled.size(); d++) {
            for (Message message : MESSAGES) {
                String label = message.getDescriptorForType().getFullName() + " in dialect " + d;
                ProtoJson fast = compiled.get(d);
                Object expected = tree(writerPath(reflective.get(d), message));

                assertThat(tree(fast.print(message))).as("print: %s", label).isEqualTo(expected);
                assertThat(tree(writerPath(fast, message)))
                        .as("DynamicMessage to writer (compiled serializer): %s", label)
                        .isEqualTo(expected);
                assertThat(generatedToWriter(fast, message))
                        .as("generated message to writer matches print: %s", label)
                        .isEqualTo(fast.print(message));
                assertThat(tree(fast.print(message.toByteArray(), message.getDescriptorForType())))
                        .as("print from bytes: %s", label)
                        .isEqualTo(expected);
                assertThat(tree(fast.print(ProtoTypes.dynamic(message, message.getDescriptorForType()))))
                        .as("print of a DynamicMessage: %s", label)
                        .isEqualTo(expected);
                assertThat(tree(reflective.get(d).print(message)))
                        .as("print without compiled serializers: %s", label)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName(
            "Given the default layout, when the compiled serializer and the reflective converter write to a writer, should give identical text")
    void given_defaultLayout_when_compiledAndReflectiveWrite_should_giveIdenticalText() {
        ProtoJson compiled = ProtoJson.defaults();
        ProtoJson reflective = ProtoJson.builder().compiledSerializers(false).build();

        for (Message message : MESSAGES) {
            assertThat(writerPath(compiled, message))
                    .as(message.getDescriptorForType().getFullName())
                    .isEqualTo(writerPath(reflective, message));
        }
    }

    @Test
    @DisplayName(
            "Given a generated message, when written into a larger document with and without the generated writer, should embed the same value")
    void given_generatedMessage_when_writtenIntoLargerDocument_should_embedSameValue() throws IOException {
        for (ProtoJson json : List.of(
                ProtoJson.defaults(),
                ProtoJson.builder().compiledSerializers(false).build())) {
            for (Message message : MESSAGES) {
                StringWriter out = new StringWriter();
                try (JsonGenerator gen = JSON.createGenerator(out)) {
                    gen.writeStartObject();
                    gen.writeFieldName("payload");
                    json.write(message, gen);
                    gen.writeNumberField("after", 1);
                    gen.writeEndObject();
                }
                assertThat(tree(out.toString()))
                        .as(
                                "%s, compiled=%s",
                                message.getDescriptorForType().getFullName(), json.usesCompiledSerializers())
                        .isEqualTo(Map.of("payload", tree(json.print(message)), "after", 1));
            }
        }
    }

    @Test
    @DisplayName(
            "Given an enum number the schema does not know, when a DynamicMessage prints through the compiled and the reflective paths, should print the number as JsonFormat does")
    void given_unknownEnumNumber_when_dynamicPrinted_should_printNumber() throws IOException {
        TypedParity.AllTypes typed = TypedParity.AllTypes.newBuilder()
                .setKindValue(99)
                .addKindsValue(7)
                .putMLk(1L, TypedParity.Kind.KIND_B)
                .build();
        DynamicMessage dynamic = ProtoTypes.dynamic(typed, typed.getDescriptorForType());

        for (ProtoJson json : List.of(
                ProtoJson.defaults(),
                ProtoJson.builder().compiledSerializers(false).build())) {
            assertThat(writerPath(json, typed))
                    .as("compiled=%s", json.usesCompiledSerializers())
                    .contains("\"kind\":99")
                    .contains("\"kinds\":[7]")
                    .contains("\"mLk\":{\"1\":\"KIND_B\"}");
            assertThat(json.toMap(dynamic)).containsEntry("kind", 99).containsEntry("kinds", List.of(7));
        }
    }

    @Test
    @DisplayName("Given a message and its serialized bytes, when both are printed, should give identical text")
    void given_messageAndItsBytes_when_printed_should_giveIdenticalText() {
        ProtoJson json = ProtoJson.builder().scalar(latLngCodec()).build();

        assertThat(json.print(VIEWPORT.toByteArray(), Viewport.getDescriptor()))
                .isEqualTo(json.print(VIEWPORT))
                .isEqualTo("{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}");
        assertThat(json.transcode(VIEWPORT.toByteArray(), Viewport.getDescriptor()))
                .isEqualTo(json.printUtf8(VIEWPORT));
    }

    @Test
    @DisplayName("Given the bytes of a single-value message, when printed, should be that value")
    void given_bytesOfSingleValueMessage_when_printed_should_beThatValue() {
        Timestamp at = ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z"));

        assertThat(ProtoJson.defaults().print(at.toByteArray(), Timestamp.getDescriptor()))
                .isEqualTo("\"2026-09-22T14:03:11Z\"");
    }

    @Test
    @DisplayName(
            "Given bytes that are not the message, when printed, should throw IllegalArgumentException naming the type")
    void given_bytesThatAreNotTheMessage_when_printed_should_throw() {
        byte[] truncated = {0x0A, 0x7F, 0x01};

        assertThatThrownBy(() -> ProtoJson.defaults().print(truncated, Interval.getDescriptor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("google.type.Interval");
    }

    @Test
    @DisplayName("Given a message that reaches Any, when printed through every path, should fall back and agree")
    void given_messageReachingAny_when_printed_should_fallBackAndAgree() throws Exception {
        Status status = Status.newBuilder()
                .setCode(3)
                .setMessage("bad")
                .addDetails(Any.pack(RetryInfo.getDefaultInstance()))
                .build();
        ProtoJson json = ProtoJson.defaults();

        Object expected =
                tree(writerPath(ProtoJson.builder().compiledSerializers(false).build(), status));

        assertThat(tree(json.print(status))).isEqualTo(expected);
        assertThat(tree(writerPath(json, status))).isEqualTo(expected);
        assertThat(tree(json.print(status.toByteArray(), Status.getDescriptor())))
                .isEqualTo(expected);
        assertThat(json.print(status)).contains("\"code\":3").contains("typeUrl");
    }

    @Test
    @DisplayName(
            "Given two compiled instances with different dialects, when each writes the same message, should each keep its own form")
    void given_twoCompiledDialects_when_writingSameMessage_should_keepOwnForm() {
        ProtoJson withCodec = ProtoJson.builder().scalar(latLngCodec()).build();
        ProtoJson plain = ProtoJson.builder().build();

        assertThat(writerPath(withCodec, VIEWPORT)).isEqualTo("{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}");
        assertThat(writerPath(plain, VIEWPORT)).startsWith("{\"low\":{\"latitude\":40.5");
        assertThat(writerPath(withCodec, VIEWPORT)).isEqualTo("{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}");
    }

    @Test
    @DisplayName(
            "Given a fresh instance used from many threads at once, when its serializers compile on first use, should print correctly on every thread")
    void given_freshInstanceOnManyThreads_when_serializersCompileOnFirstUse_should_printCorrectly() throws Exception {
        ProtoJson json = ProtoJson.builder().build();
        BadRequest message = (BadRequest) MESSAGES.get(4);
        String expected =
                writerPath(ProtoJson.builder().compiledSerializers(false).build(), message);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) results.add(pool.submit(() -> writerPath(json, message)));
            for (Future<String> result : results) assertThat(result.get()).isEqualTo(expected);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

    /**
     * The path that skips the wire transcoder: a DynamicMessage goes to a compiled serializer when
     * the instance has one for the default layout, to the reflective converter otherwise.
     */
    private static String writerPath(ProtoJson json, Message message) {
        StringWriter out = new StringWriter();
        json.print(ProtoTypes.dynamic(message, message.getDescriptorForType()), out);
        return out.toString();
    }

    private static String generatedToWriter(ProtoJson json, Message message) {
        StringWriter out = new StringWriter();
        json.print(message, out);
        return out.toString();
    }

    private static Object tree(String json) throws IOException {
        try (JsonParser parser = JSON.createParser(json)) {
            parser.nextToken();
            return JsonTrees.read(parser);
        }
    }

    private static ProtoScalarCodec latLngCodec() {
        return ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] parts = text.split(",");
                    if (parts.length != 2) throw new IllegalArgumentException("expected lat,lng");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(parts[0]))
                            .setLongitude(Double.parseDouble(parts[1]))
                            .build();
                });
    }
}
