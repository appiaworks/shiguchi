package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.type.LatLng;
import com.google.type.LatLngProto;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoMapConverter;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An application's {@link ProtoScalarCodec} must come out the same from every serde tier: Tier 0
 * ({@link WireJsonTranscoder}), Tier 2 ({@link DynamicSerializerFactory}) and the reflective
 * {@link ProtoMapConverter}.
 *
 * <p>The codec is installed before the serializers are compiled and stays for the whole class,
 * because Tier 0 and Tier 2 bake in which types are scalars when they are built.
 */
class ScalarCodecTierParityTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static Descriptor placeDescriptor;
    private static AbstractDynamicSerializer placeSerializer;
    private static WireJsonTranscoder transcoder;

    @BeforeAll
    static void installCodecThenCompile() throws Exception {
        Assumptions.assumeTrue(
                Codegen.available(),
                "needs the serializer code generator: Java 25+ with protobuf-java-extras-classfile");
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(latLngCodec()));

        placeDescriptor = buildPlaceFile().findMessageTypeByName("Place");
        placeSerializer = new DynamicSerializerFactory().compileDescriptor(placeDescriptor);
        transcoder = new WireJsonTranscoder();
    }

    @AfterAll
    static void restoreMappingDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    @Test
    @DisplayName(
            "Given a Place with a custom-codec field, when Tier 0, Tier 2 and ProtoMapConverter serialize, should emit identical JSON")
    void given_placeWithCodecField_when_allTiersSerialize_should_emitIdenticalJson() throws Exception {
        DynamicMessage place = place();

        JsonNode tier2 = OBJECT_MAPPER.readTree(tier2Serialize(place));
        JsonNode tier0 = OBJECT_MAPPER.readTree(new String(
                transcoder.transcode(transcoder.programFor(placeDescriptor), place.toByteArray()),
                StandardCharsets.UTF_8));
        JsonNode reflective =
                OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(ProtoMapConverter.dynamicToMap(place)));

        assertThat(tier2.get("spot").asText()).isEqualTo("40.5,-73.25");
        assertThat(tier2.get("path")).hasSize(2);
        assertThat(tier2.get("path").get(1).asText()).isEqualTo("3.0,4.0");
        assertThat(tier0).as("Tier 0 vs Tier 2").isEqualTo(tier2);
        assertThat(reflective).as("reflective vs Tier 2").isEqualTo(tier2);
    }

    @Test
    @DisplayName(
            "Given a Place, when Tier 2 writes it to a map, should hold the codec's text for single and repeated fields")
    void given_place_when_tier2ToMap_should_holdCodecText() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) placeSerializer.toMap(place());

        assertThat(map.get("spot")).isEqualTo("40.5,-73.25");
        assertThat(map.get("path")).isEqualTo(List.of("1.0,2.0", "3.0,4.0"));
    }

    @Test
    @DisplayName("Given a map with the codec's text, when Tier 2 reads it, should build the same message")
    void given_mapWithCodecText_when_tier2FromMap_should_buildSameMessage() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spot", "40.5,-73.25");
        map.put("path", List.of("1.0,2.0", "3.0,4.0"));
        map.put("name", "Pier");

        DynamicMessage result = placeSerializer.fromMap(map, DynamicMessage.newBuilder(placeDescriptor));

        assertThat(result).isEqualTo(place());
    }

    @Test
    @DisplayName("Given JSON text with the codec's text, when Tier 2 deserializes, should build the same message")
    void given_jsonWithCodecText_when_tier2Deserialize_should_buildSameMessage() throws Exception {
        String json = "{\"spot\":\"40.5,-73.25\",\"path\":[\"1.0,2.0\",\"3.0,4.0\"],\"name\":\"Pier\"}";

        JsonParser parser = JSON_FACTORY.createParser(new StringReader(json));
        parser.nextToken();
        DynamicMessage result = placeSerializer.deserialize(parser, DynamicMessage.newBuilder(placeDescriptor));

        assertThat(result).isEqualTo(place());
    }

    @Test
    @DisplayName("Given a map with the codec's text, when ProtoMapConverter reads it, should build the same message")
    void given_mapWithCodecText_when_reflectiveFromMap_should_buildSameMessage() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("spot", "40.5,-73.25");
        map.put("path", List.of("1.0,2.0", "3.0,4.0"));
        map.put("name", "Pier");

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(map, placeDescriptor);

        assertThat(result).isEqualTo(place());
    }

    @Test
    @DisplayName(
            "Given a codec is installed, when asked whether proto3-only serializers may serve the message, should say no")
    void given_codecInstalled_when_needsGatewaySerde_should_beTrue() {
        assertThat(ProtoJsonMapping.needsGatewaySerde(placeDescriptor)).isTrue();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────

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

    private static DynamicMessage place() {
        return DynamicMessage.newBuilder(placeDescriptor)
                .setField(placeDescriptor.findFieldByName("spot"), latLng(40.5, -73.25))
                .addRepeatedField(placeDescriptor.findFieldByName("path"), latLng(1, 2))
                .addRepeatedField(placeDescriptor.findFieldByName("path"), latLng(3, 4))
                .setField(placeDescriptor.findFieldByName("name"), "Pier")
                .build();
    }

    private static DynamicMessage latLng(double latitude, double longitude) {
        return ProtoTypes.dynamic(
                LatLng.newBuilder()
                        .setLatitude(latitude)
                        .setLongitude(longitude)
                        .build(),
                LatLng.getDescriptor());
    }

    private static FileDescriptor buildPlaceFile() throws Exception {
        DescriptorProto place = DescriptorProto.newBuilder()
                .setName("Place")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("spot")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.LatLng"))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("path")
                        .setNumber(2)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.LatLng"))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("name")
                        .setNumber(3)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("codec_parity.proto")
                .setPackage("codecparity")
                .setSyntax("proto3")
                .addDependency("google/type/latlng.proto")
                .addMessageType(place)
                .build();

        return FileDescriptor.buildFrom(file, new FileDescriptor[] {LatLngProto.getDescriptor()});
    }

    private static String tier2Serialize(DynamicMessage msg) throws Exception {
        StringWriter out = new StringWriter();
        JsonGenerator gen = JSON_FACTORY.createGenerator(out);
        placeSerializer.serialize(msg, gen);
        gen.close();
        return out.toString();
    }
}
