package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.type.LatLng;
import com.google.type.LatLngProto;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for ProtoScalarCodec registry (ProtoJsonMapping), focusing on codec registration,
 * retrieval, and integration with ProtoWkt serialization/deserialization.
 */
class ProtoScalarCodecRegistryTest {

    private static final ProtoScalarCodec LATLNG_CODEC = ProtoScalarCodec.ofString(
            LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                String[] p = text.split(",");
                if (p.length != 2) throw new IllegalArgumentException("expected lat,lng");
                return LatLng.newBuilder()
                        .setLatitude(Double.parseDouble(p[0]))
                        .setLongitude(Double.parseDouble(p[1]))
                        .build();
            });

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 1: No codec installed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given no codec installed, when isMapped is called on LatLng descriptor, should return false")
    void given_noCodecInstalled_when_isMappedLatLng_should_returnFalse() {
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        boolean mapped = ProtoJsonMapping.isMapped(LatLng.getDescriptor());

        assertThat(mapped).isFalse();
    }

    @Test
    @DisplayName("Given no codec installed, when isWkt is called on LatLng descriptor, should return false")
    void given_noCodecInstalled_when_isWktLatLng_should_returnFalse() {
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        boolean isWkt = ProtoWkt.isWkt(LatLng.getDescriptor());

        assertThat(isWkt).isFalse();
    }

    @Test
    @DisplayName("Given no codec installed, when codecFor is called on LatLng descriptor, should return null")
    void given_noCodecInstalled_when_codecForLatLng_should_returnNull() {
        ProtoJsonMapping.install(ProtoJsonMapping.PROTO3);

        ProtoScalarCodec codec = ProtoJsonMapping.codecFor(LatLng.getDescriptor());

        assertThat(codec).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 2: Codec installed
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given custom LatLng codec installed, when isMapped is called, should return true")
    void given_customLatLngCodecInstalled_when_isMapped_should_returnTrue() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        boolean mapped = ProtoJsonMapping.isMapped(LatLng.getDescriptor());

        assertThat(mapped).isTrue();
    }

    @Test
    @DisplayName("Given custom LatLng codec installed, when isWkt is called, should return true")
    void given_customLatLngCodecInstalled_when_isWkt_should_returnTrue() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        boolean isWkt = ProtoWkt.isWkt(LatLng.getDescriptor());

        assertThat(isWkt).isTrue();
    }

    @Test
    @DisplayName("Given custom LatLng codec installed, when codecFor is called, should return the same codec instance")
    void given_customLatLngCodecInstalled_when_codecFor_should_returnSameInstance() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        ProtoScalarCodec codec = ProtoJsonMapping.codecFor(LatLng.getDescriptor());

        assertThat(codec).isSameAs(LATLNG_CODEC);
    }

    @Test
    @DisplayName("Given custom LatLng codec installed, when customCodecFor is called, should return it")
    void given_customLatLngCodecInstalled_when_customCodecFor_should_returnIt() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        ProtoScalarCodec codec = ProtoJsonMapping.customCodecFor(LatLng.getDescriptor());

        assertThat(codec).isSameAs(LATLNG_CODEC);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 3: Built-in google.type.Date under DEFAULTS
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given DEFAULTS mapping with Date, when codecFor Date descriptor is called, should return non-null")
    void given_defaultsMappingDate_when_codecForDate_should_returnNonNull() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);

        ProtoScalarCodec codec = ProtoJsonMapping.codecFor(com.google.type.Date.getDescriptor());

        assertThat(codec).isNotNull();
    }

    @Test
    @DisplayName("Given DEFAULTS mapping with Date, when customCodecFor Date descriptor is called, should return null")
    void given_defaultsMappingDate_when_customCodecForDate_should_returnNull() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);

        ProtoScalarCodec codec = ProtoJsonMapping.customCodecFor(com.google.type.Date.getDescriptor());

        assertThat(codec).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 4: install(DEFAULTS) without codecs removes custom codec
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given custom LatLng codec installed, when install DEFAULTS without codecs, should remove it")
    void given_customCodecInstalled_when_installDefaultsNoCodecs_should_removeCustom() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));
        assertThat(ProtoJsonMapping.isMapped(LatLng.getDescriptor())).isTrue();

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);

        assertThat(ProtoJsonMapping.isMapped(LatLng.getDescriptor())).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 5: Two codecs with same fullName -> install throws
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given two codecs for same fullName, when install is called, should throw IllegalArgumentException")
    void given_twoCodecsForSameType_when_install_should_throwIllegalArgumentException() {
        ProtoScalarCodec latLng2 = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(),
                ll -> String.valueOf(ll.getLatitude()),
                s -> LatLng.newBuilder().setLatitude(Double.parseDouble(s)).build());

        assertThatThrownBy(() -> ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC, latLng2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("google.type.LatLng");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 6: Codec for google.protobuf.Timestamp -> install throws
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given codec for google.protobuf.Timestamp, when install is called, should throw IllegalArgumentException")
    void given_codecForTimestamp_when_install_should_throwIllegalArgumentException() {
        ProtoScalarCodec timestampCodec = new ProtoScalarCodec() {
            @Override
            public String fullName() {
                return "google.protobuf.Timestamp";
            }

            @Override
            public Object toJson(Message message) {
                return "timestamp";
            }

            @Override
            public Message fromJson(Object json, Descriptor descriptor) {
                return null;
            }
        };

        assertThatThrownBy(() -> ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(timestampCodec)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("google.protobuf");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 7: Codec with blank fullName -> install throws
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given codec with blank fullName, when install is called, should throw IllegalArgumentException")
    void given_codecWithBlankFullName_when_install_should_throwIllegalArgumentException() {
        ProtoScalarCodec blankCodec = new ProtoScalarCodec() {
            @Override
            public String fullName() {
                return "  ";
            }

            @Override
            public Object toJson(Message message) {
                return "blank";
            }

            @Override
            public Message fromJson(Object json, Descriptor descriptor) {
                return null;
            }
        };

        assertThatThrownBy(() -> ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(blankCodec)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no message type");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 8: Custom codec replaces built-in Date
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given custom Date codec installed, when customCodecFor is called, should return it")
    void given_customDateCodecInstalled_when_customCodecFor_should_returnIt() {
        ProtoScalarCodec customDate = ProtoScalarCodec.ofString(
                com.google.type.Date.getDefaultInstance(),
                d -> "D" + d.getYear(),
                s -> com.google.type.Date.newBuilder()
                        .setYear(Integer.parseInt(s.substring(1)))
                        .build());

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(customDate));

        ProtoScalarCodec codec = ProtoJsonMapping.customCodecFor(com.google.type.Date.getDescriptor());

        assertThat(codec).isSameAs(customDate);
    }

    @Test
    @DisplayName("Given custom Date codec, when toJsonValue on Date DynamicMessage, should use custom codec")
    void given_customDateCodec_when_toJsonValueDate_should_useCustomCodec() {
        ProtoScalarCodec customDate = ProtoScalarCodec.ofString(
                com.google.type.Date.getDefaultInstance(),
                d -> "D" + d.getYear(),
                s -> com.google.type.Date.newBuilder()
                        .setYear(Integer.parseInt(s.substring(1)))
                        .build());
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(customDate));

        DynamicMessage dateMsg = DynamicMessage.newBuilder(com.google.type.Date.getDescriptor())
                .setField(com.google.type.Date.getDescriptor().findFieldByName("year"), 2026)
                .build();

        Object result = ProtoWkt.toJsonValue(dateMsg);

        assertThat(result).isEqualTo("D2026");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 9: reachesMapped cache clearing
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given container with LatLng field, when reachesMapped before/after install, should reflect change")
    void given_containerWithLatLngField_when_reachesMappedBeforeAfterInstall_should_reflectChange() throws Exception {
        Descriptor containerDesc = buildContainerDescriptor();

        boolean beforeInstall = ProtoJsonMapping.reachesMapped(containerDesc);
        assertThat(beforeInstall).isFalse();

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        boolean afterInstall = ProtoJsonMapping.reachesMapped(containerDesc);
        assertThat(afterInstall).isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 10: ProtoWkt.toJsonValue returns custom codec output
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given LatLng DynamicMessage with lat=40.5 lng=-73.25, when toJsonValue, should return string")
    void given_latLngDynamic_when_toJsonValue_should_returnString() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        DynamicMessage latLng = DynamicMessage.newBuilder(LatLng.getDescriptor())
                .setField(LatLng.getDescriptor().findFieldByName("latitude"), 40.5)
                .setField(LatLng.getDescriptor().findFieldByName("longitude"), -73.25)
                .build();

        Object result = ProtoWkt.toJsonValue(latLng);

        assertThat(result).isEqualTo("40.5,-73.25");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 11: ProtoMapConverter.dynamicToMap with custom codec
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given container with LatLng field named spot, when dynamicToMap, should use codec for spot field")
    void given_containerWithLatLngSpot_when_dynamicToMap_should_useCodecForSpot() throws Exception {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        Descriptor containerDesc = buildContainerDescriptor();
        DynamicMessage latLng = DynamicMessage.newBuilder(LatLng.getDescriptor())
                .setField(LatLng.getDescriptor().findFieldByName("latitude"), 40.5)
                .setField(LatLng.getDescriptor().findFieldByName("longitude"), -73.25)
                .build();

        DynamicMessage container = DynamicMessage.newBuilder(containerDesc)
                .setField(containerDesc.findFieldByName("spot"), latLng)
                .build();

        Map<String, Object> result = ProtoMapConverter.dynamicToMap(container);

        assertThat(result).containsEntry("spot", "40.5,-73.25");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 12: ProtoWkt.fromJava round-trip
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given string 40.5,-73.25, when fromJava on LatLng descriptor, should return DynamicMessage")
    void given_latLngString_when_fromJava_should_returnDynamicMessage() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        DynamicMessage result = ProtoWkt.fromJava("40.5,-73.25", LatLng.getDescriptor());

        assertThat(result).isNotNull();
        assertThat(result.getDescriptorForType()).isSameAs(LatLng.getDescriptor());
        assertThat((Double) result.getField(LatLng.getDescriptor().findFieldByName("latitude")))
                .isEqualTo(40.5);
        assertThat((Double) result.getField(LatLng.getDescriptor().findFieldByName("longitude")))
                .isEqualTo(-73.25);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 13: fromJava with null value
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given null value, when fromJava on LatLng descriptor, should return null")
    void given_nullValue_when_fromJava_should_returnNull() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        DynamicMessage result = ProtoWkt.fromJava(null, LatLng.getDescriptor());

        assertThat(result).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 14: fromJava with malformed input
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given malformed string garbage, when fromJava on LatLng descriptor, should throw ProtoJsonException")
    void given_malformedString_when_fromJava_should_throwProtoJsonException() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        assertThatThrownBy(() -> ProtoWkt.fromJava("garbage", LatLng.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("google.type.LatLng")
                .hasMessageContaining("garbage");
    }

    @Test
    @DisplayName("Given malformed string, when fromJava, should name the codec's type")
    void given_malformedString_when_fromJava_should_nameTheType() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        assertThatThrownBy(() -> ProtoWkt.fromJava("garbage", LatLng.getDescriptor()))
                .isInstanceOfSatisfying(
                        ProtoJsonException.class, e -> assertThat(e.typeName()).isEqualTo("google.type.LatLng"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 15: fromJava with wrong type (Integer for STRING codec)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Integer 42 for STRING codec, when fromJava, should throw ProtoJsonException")
    void given_integerForStringCodec_when_fromJava_should_throwProtoJsonException() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        assertThatThrownBy(() -> ProtoWkt.fromJava(42, LatLng.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("expected a string");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 16: Object form (Map) still accepted for non-ANY codec
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given Map with lat/lng for STRING codec, when fromJava, should read as object form")
    void given_mapForStringCodec_when_fromJava_should_readAsObjectForm() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        DynamicMessage result = ProtoWkt.fromJava(Map.of("latitude", 1.5, "longitude", 2.5), LatLng.getDescriptor());

        assertThat((Double) result.getField(LatLng.getDescriptor().findFieldByName("latitude")))
                .isEqualTo(1.5);
        assertThat((Double) result.getField(LatLng.getDescriptor().findFieldByName("longitude")))
                .isEqualTo(2.5);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 17: Codec that throws ProtoJsonException is rethrown unchanged
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given codec that throws ProtoJsonException, when fromJava, should rethrow that same exception")
    void given_codecThrowsProtoJsonException_when_fromJava_should_rethrowIt() {
        ProtoJsonException notAllowed = new ProtoJsonException("google.type.LatLng", "any value", "not allowed", null);
        ProtoScalarCodec failingCodec = new ProtoScalarCodec() {
            @Override
            public String fullName() {
                return "google.type.LatLng";
            }

            @Override
            public Object toJson(Message message) {
                return null;
            }

            @Override
            public Message fromJson(Object json, Descriptor descriptor) {
                throw notAllowed;
            }
        };

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(failingCodec));

        assertThatThrownBy(() -> ProtoWkt.fromJava("any value", LatLng.getDescriptor()))
                .isSameAs(notAllowed);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 18: Codec with JsonShape.ANY
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given codec with JsonShape.ANY that returns List, when toJsonValue, should return the List")
    void given_anyShapeCodecReturningList_when_toJsonValue_should_returnList() {
        ProtoScalarCodec anyCodec = ProtoScalarCodec.of(
                LatLng.getDefaultInstance(),
                JsonShape.ANY,
                ll -> List.of(ll.getLatitude(), ll.getLongitude()),
                json -> {
                    List<?> l = (List<?>) json;
                    return LatLng.newBuilder()
                            .setLatitude(((Number) l.get(0)).doubleValue())
                            .setLongitude(((Number) l.get(1)).doubleValue())
                            .build();
                });

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(anyCodec));

        DynamicMessage latLng = DynamicMessage.newBuilder(LatLng.getDescriptor())
                .setField(LatLng.getDescriptor().findFieldByName("latitude"), 40.5)
                .setField(LatLng.getDescriptor().findFieldByName("longitude"), -73.25)
                .build();

        Object result = ProtoWkt.toJsonValue(latLng);

        assertThat(result).isEqualTo(List.of(40.5, -73.25));
    }

    @Test
    @DisplayName("Given JsonShape.ANY codec, when fromJava with List, should round-trip")
    void given_anyShapeCodec_when_fromJavaWithList_should_roundTrip() {
        ProtoScalarCodec anyCodec = ProtoScalarCodec.of(
                LatLng.getDefaultInstance(),
                JsonShape.ANY,
                ll -> List.of(ll.getLatitude(), ll.getLongitude()),
                json -> {
                    List<?> l = (List<?>) json;
                    return LatLng.newBuilder()
                            .setLatitude(((Number) l.get(0)).doubleValue())
                            .setLongitude(((Number) l.get(1)).doubleValue())
                            .build();
                });

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(anyCodec));

        DynamicMessage result = ProtoWkt.fromJava(List.of(40.5, -73.25), LatLng.getDescriptor());

        assertThat((Double) result.getField(LatLng.getDescriptor().findFieldByName("latitude")))
                .isEqualTo(40.5);
        assertThat((Double) result.getField(LatLng.getDescriptor().findFieldByName("longitude")))
                .isEqualTo(-73.25);
    }

    @Test
    @DisplayName("Given JsonShape.ANY codec, when fromJava with Map, should throw ProtoJsonException")
    void given_anyShapeCodecWithList_when_fromJavaWithMap_should_throwProtoJsonException() {
        ProtoScalarCodec anyCodec = ProtoScalarCodec.of(
                LatLng.getDefaultInstance(),
                JsonShape.ANY,
                ll -> List.of(ll.getLatitude(), ll.getLongitude()),
                json -> {
                    List<?> l = (List<?>) json;
                    return LatLng.newBuilder()
                            .setLatitude(((Number) l.get(0)).doubleValue())
                            .setLongitude(((Number) l.get(1)).doubleValue())
                            .build();
                });

        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(anyCodec));

        assertThatThrownBy(() -> ProtoWkt.fromJava(Map.of("latitude", 1.5), LatLng.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class)
                .hasMessageContaining("google.type.LatLng");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 19: ProtoMapConverter.dynamicFromMap with custom codec
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given map with spot string, when dynamicFromMap, should use codec to build LatLng")
    void given_mapWithSpotString_when_dynamicFromMap_should_useCodecForSpot() throws Exception {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(LATLNG_CODEC));

        Descriptor containerDesc = buildContainerDescriptor();
        Map<String, Object> map = Map.of("spot", "40.5,-73.25");

        DynamicMessage result = ProtoMapConverter.dynamicFromMap(map, containerDesc);

        DynamicMessage spot = (DynamicMessage) result.getField(containerDesc.findFieldByName("spot"));
        assertThat((Double) spot.getField(LatLng.getDescriptor().findFieldByName("latitude")))
                .isEqualTo(40.5);
        assertThat((Double) spot.getField(LatLng.getDescriptor().findFieldByName("longitude")))
                .isEqualTo(-73.25);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Scenario 20: Factory methods
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given ofString codec, when fullName is called, should return google.type.LatLng")
    void given_ofStringCodec_when_fullName_should_returnLatLngFullName() {
        ProtoScalarCodec codec = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        assertThat(codec.fullName()).isEqualTo("google.type.LatLng");
    }

    @Test
    @DisplayName("Given ofString codec, when jsonShape is called, should return STRING")
    void given_ofStringCodec_when_jsonShape_should_returnString() {
        ProtoScalarCodec codec = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        assertThat(codec.jsonShape()).isEqualTo(JsonShape.STRING);
    }

    @Test
    @DisplayName("Given ofString codec, when toJson is called with generated LatLng, should return string")
    void given_ofStringCodec_when_toJsonGeneratedLatLng_should_returnString() {
        ProtoScalarCodec codec = LATLNG_CODEC;
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(codec));

        LatLng generated =
                LatLng.newBuilder().setLatitude(40.5).setLongitude(-73.25).build();

        Object result = codec.toJson(generated);

        assertThat(result).isEqualTo("40.5,-73.25");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ──────────────────────────────────────────────────────────────────────────

    private static Descriptor buildContainerDescriptor() throws Exception {
        DescriptorProto containerProto = DescriptorProto.newBuilder()
                .setName("Container")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("spot")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("google.type.LatLng")
                        .build())
                .build();

        FileDescriptorProto fileProto = FileDescriptorProto.newBuilder()
                .setName("container.proto")
                .setPackage("test")
                .setSyntax("proto3")
                .addMessageType(containerProto)
                .build();

        return FileDescriptor.buildFrom(fileProto, new FileDescriptor[] {LatLngProto.getDescriptor()})
                .findMessageTypeByName("Container");
    }
}
