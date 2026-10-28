package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.type.Date;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.JsonDialect;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the Tier 1 end-to-end path: compiled-class detection, typed gRPC response
 * parsing via the generated stub parser, and typed-writer JSON writing whose output must
 * match JsonFormat.
 */
class StaticSerdeDetectorTier1Test {

    @BeforeAll
    static void requiresCodegen() {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
    }

    private StaticSerdeDetector detector;
    private ServiceEndpointContext ctx;

    @BeforeEach
    void scanTimestampFile() {
        detector = new StaticSerdeDetector();
        ctx = new ServiceEndpointContext(new ServiceDefinition(), null);
        ctx.setFileDescriptors(Map.of(
                "google/protobuf/timestamp.proto", Timestamp.getDescriptor().getFile()));
        detector.scan(ctx);
    }

    @Test
    @DisplayName(
            "Given a descriptor whose compiled class is on the classpath, when scanned, should expose its default instance")
    void given_compiledClassOnClasspath_when_scanned_should_exposeDefaultInstance() {
        Message instance = detector.defaultInstanceFor(Timestamp.getDescriptor());

        assertThat(instance).isInstanceOf(Timestamp.class);
        assertThat(detector.isRegistered(Timestamp.getDescriptor())).isTrue();
    }

    @Test
    @DisplayName(
            "Given a descriptor with no compiled class, when scanned, should return null so dispatch stays dynamic")
    void given_noCompiledClass_when_scanned_should_returnNull() throws Exception {
        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("nope.proto")
                        .setSyntax("proto3")
                        .setPackage("no.such.pkg")
                        .addMessageType(DescriptorProto.newBuilder()
                                .setName("Missing")
                                .addField(FieldDescriptorProto.newBuilder()
                                        .setName("x")
                                        .setNumber(1)
                                        .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                        .build(),
                new FileDescriptor[0]);
        ctx.setFileDescriptors(Map.of("nope.proto", file));
        detector.scan(ctx);

        assertThat(detector.defaultInstanceFor(file.findMessageTypeByName("Missing")))
                .isNull();
        // Earlier registrations survive later scans (shared marshaller must not be clobbered)
        assertThat(detector.defaultInstanceFor(Timestamp.getDescriptor())).isNotNull();
    }

    @Test
    @DisplayName("Given a Tier 1 typed message, when serializeToBytes, should produce the same JSON as JsonFormat")
    void given_typedMessage_when_serializeToBytes_should_matchJsonFormat() throws Exception {
        SerdeSelector selector = new SerdeSelector(detector, new DynamicProtoSerde(new GrpcDescriptorCache()));
        Timestamp ts = Timestamp.newBuilder()
                .setSeconds(1_700_000_000L)
                .setNanos(500_000_000)
                .build();

        byte[] tier1Json = selector.serializeToBytes(ts, Timestamp.getDescriptor(), ctx, null);

        String expected = JsonFormat.printer().omittingInsignificantWhitespace().print(ts);
        assertThat(new String(tier1Json, StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given a Tier 1 method descriptor, when the response is parsed, should yield the generated type not DynamicMessage")
    void given_tier1MethodDescriptor_when_responseParsed_should_yieldGeneratedType() throws Exception {
        GrpcDescriptorCache cache = new GrpcDescriptorCache();
        var serviceProto = FileDescriptorProto.newBuilder()
                .setName("svc.proto")
                .setSyntax("proto3")
                .setPackage("t")
                .addDependency("google/protobuf/timestamp.proto")
                .addService(com.google.protobuf.DescriptorProtos.ServiceDescriptorProto.newBuilder()
                        .setName("Clock")
                        .addMethod(com.google.protobuf.DescriptorProtos.MethodDescriptorProto.newBuilder()
                                .setName("Now")
                                .setInputType(".google.protobuf.Timestamp")
                                .setOutputType(".google.protobuf.Timestamp")))
                .build();
        FileDescriptor file = FileDescriptor.buildFrom(
                serviceProto, new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
        var sd = file.findServiceByName("Clock");
        var md = sd.getMethods().get(0);

        var typed = cache.resolveResponseAsMessage(
                ctx, sd, md, io.grpc.MethodDescriptor.MethodType.UNARY, Timestamp.getDefaultInstance());
        Timestamp original =
                Timestamp.newBuilder().setSeconds(123).setNanos(456).build();

        Message parsed = typed.parseResponse(new ByteArrayInputStream(original.toByteArray()));

        assertThat(parsed).isInstanceOf(Timestamp.class).isEqualTo(original);

        // Null default instance degrades to the dynamic descriptor
        var dynamic = cache.resolveResponseAsMessage(ctx, sd, md, io.grpc.MethodDescriptor.MethodType.UNARY, null);
        assertThat(dynamic.parseResponse(new ByteArrayInputStream(original.toByteArray())))
                .isInstanceOf(com.google.protobuf.DynamicMessage.class);
    }

    @Test
    @DisplayName(
            "Given compiled classes with map and nested message fields, when Tier 1 writes them, should match JsonFormat")
    void given_mapAndNestedFields_when_tier1Writes_should_matchJsonFormat() throws Exception {
        ctx.setFileDescriptors(Map.of(
                "google/rpc/error_details.proto", ErrorInfo.getDescriptor().getFile()));
        detector.scan(ctx);
        SerdeSelector selector = new SerdeSelector(detector, new DynamicProtoSerde(new GrpcDescriptorCache()));
        ErrorInfo info = ErrorInfo.newBuilder()
                .setReason("RATE_LIMITED")
                .setDomain("example.com")
                .putMetadata("quota", "10")
                .putMetadata("window", "1m")
                .build();
        BadRequest bad = BadRequest.newBuilder()
                .addFieldViolations(
                        BadRequest.FieldViolation.newBuilder().setField("name").setDescription("required"))
                .addFieldViolations(BadRequest.FieldViolation.newBuilder().setField("age"))
                .build();

        for (Message typed : List.of(info, bad)) {
            assertThat(detector.defaultInstanceFor(typed.getDescriptorForType()))
                    .isNotNull();
            byte[] json = selector.serializeToBytes(typed, typed.getDescriptorForType(), ctx, null);

            @SuppressWarnings("deprecation")
            String expected = JsonFormat.printer()
                    .includingDefaultValueFields()
                    .omittingInsignificantWhitespace()
                    .print(typed);
            assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName(
            "Given a google.type message the mapping writes as text, when Tier 1 serves it, should write the text form")
    void given_mappedGoogleType_when_tier1Serves_should_writeTextForm() throws Exception {
        JsonDialect.of(ProtoJsonMapping.DEFAULTS, List.of()).<Void, Exception>call(() -> {
            ctx.setFileDescriptors(
                    Map.of("google/type/date.proto", Date.getDescriptor().getFile()));
            detector.scan(ctx);
            Date date = Date.newBuilder().setYear(2026).setMonth(9).setDay(22).build();

            assertThat(detector.defaultInstanceFor(Date.getDescriptor()))
                    .as("a mapped type is served by Tier 1 now that the writer applies the mapping")
                    .isNotNull();
            SerdeSelector selector = new SerdeSelector(detector, new DynamicProtoSerde(new GrpcDescriptorCache()));
            byte[] json = selector.serializeToBytes(date, Date.getDescriptor(), ctx, null);
            assertThat(new String(json, StandardCharsets.UTF_8)).isEqualTo("\"2026-09-22\"");
            return null;
        });
    }
}
