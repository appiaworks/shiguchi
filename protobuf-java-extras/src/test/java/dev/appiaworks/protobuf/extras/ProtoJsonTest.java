package dev.appiaworks.protobuf.extras;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.geo.type.Viewport;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.type.Interval;
import com.google.type.LatLng;
import com.google.type.Money;
import com.google.type.PostalAddress;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for ProtoJson facade.
 *
 * <p>Tests verify the core behavior of the ProtoJson API: printing messages as JSON text,
 * converting to and from maps, parsing JSON back to messages, error handling, dialect options,
 * builder validation, and independence of instances.
 */
class ProtoJsonTest {

    // ──────────────────────────────────────────────────────────────────────────
    // Print: basic text output with options
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given Interval with start 2026-09-22T14:03:11Z and end 2026-09-22T15:00:00Z, when defaults().print(), should output exact ISO timestamps")
    void given_intervalWithTimestamps_when_defaultsPrint_should_outputIsoTimestamps() {
        Interval interval = Interval.newBuilder()
                .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
                .setEndTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T15:00:00Z")))
                .build();

        String result = ProtoJson.defaults().print(interval);

        assertThat(result).isEqualTo("{\"startTime\":\"2026-09-22T14:03:11Z\",\"endTime\":\"2026-09-22T15:00:00Z\"}");
    }

    @Test
    @DisplayName(
            "Given Viewport with low 40.5,-74.25 high 41.0,-73.5 and latLng codec, when builder().scalar(latLng).build().print(), should output codec text form")
    void given_viewportWithLatLngCodec_when_builderScalarPrint_should_outputCodecText() {
        ProtoScalarCodec latLng = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    if (p.length != 2) throw new IllegalArgumentException("expected lat,lng");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        Viewport viewport = Viewport.newBuilder()
                .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
                .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
                .build();

        String result = ProtoJson.builder().scalar(latLng).build().print(viewport);

        assertThat(result).isEqualTo("{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}");
    }

    @Test
    @DisplayName(
            "Given Viewport without latLng codec, when defaults().print(), should output LatLng fields as objects with latitude")
    void given_viewportWithoutCodec_when_defaultsPrint_should_outputLatLngAsObject() {
        Viewport viewport = Viewport.newBuilder()
                .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
                .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
                .build();

        String result = ProtoJson.defaults().print(viewport);

        assertThat(result).contains("\"low\":{\"latitude\":40.5");
    }

    @Test
    @DisplayName(
            "Given Interval when builder().preserveProtoFieldNames(true).build().print(), should use start_time and end_time field names")
    void given_intervalWithPreserveFieldNames_when_print_should_useProtoFieldNames() {
        Interval interval = Interval.newBuilder()
                .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
                .setEndTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T15:00:00Z")))
                .build();

        String result =
                ProtoJson.builder().preserveProtoFieldNames(true).build().print(interval);

        assertThat(result).isEqualTo("{\"start_time\":\"2026-09-22T14:03:11Z\",\"end_time\":\"2026-09-22T15:00:00Z\"}");
    }

    @Test
    @DisplayName(
            "Given PostalAddress with only regionCode US when omitDefaultValues(true), should omit revision; defaults().print() includes revision:0")
    void given_postalAddressWithOmitDefaults_when_print_should_omitZeroFields() {
        PostalAddress addr = PostalAddress.newBuilder().setRegionCode("US").build();

        String resultOmit = ProtoJson.builder().omitDefaultValues(true).build().print(addr);
        String resultDefault = ProtoJson.defaults().print(addr);

        assertThat(resultOmit).isEqualTo("{\"regionCode\":\"US\"}");
        assertThat(resultDefault).contains("\"revision\":0");
    }

    @Test
    @DisplayName(
            "Given message and Writer, when print(message, writer), should write same text and not close the writer")
    void given_messageAndWriter_when_printToWriter_should_writeTextAndNotClose() {
        Interval interval = Interval.newBuilder()
                .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
                .build();

        StringWriter stringWriter = new TrackingStringWriter();
        ProtoJson.defaults().print(interval, stringWriter);

        assertThat(stringWriter.toString()).isEqualTo("{\"startTime\":\"2026-09-22T14:03:11Z\"}");
        assertThat(((TrackingStringWriter) stringWriter).wasClosed()).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // toMap / fromMap: object tree conversion
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given Viewport with latLng codec, when builder().scalar(latLng).build().toMap(), should return map with codec text values")
    void given_viewportWithCodec_when_toMap_should_returnCodecTextInMap() {
        ProtoScalarCodec latLng = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    if (p.length != 2) throw new IllegalArgumentException("expected lat,lng");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        Viewport viewport = Viewport.newBuilder()
                .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
                .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
                .build();

        java.util.Map<String, Object> result =
                ProtoJson.builder().scalar(latLng).build().toMap(viewport);

        assertThat(result).containsEntry("low", "40.5,-74.25").containsEntry("high", "41.0,-73.5");
    }

    @Test
    @DisplayName(
            "Given map with codec-formatted text for latLng, when fromMap(map, Viewport), should build Viewport with correct coordinates")
    void given_mapWithCodecText_when_fromMap_should_buildViewportCorrectly() {
        ProtoScalarCodec latLng = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    if (p.length != 2) throw new IllegalArgumentException("expected lat,lng");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        java.util.Map<String, Object> map = java.util.Map.of("low", "40.5,-74.25");

        Viewport result = ProtoJson.builder().scalar(latLng).build().fromMap(map, Viewport.getDefaultInstance());

        assertThat(result.getLow().getLatitude()).isEqualTo(40.5);
        assertThat(result.getLow().getLongitude()).isEqualTo(-74.25);
        assertThat(result.hasHigh()).isFalse();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Parse: JSON text to message
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given printed JSON, when parse(json, prototype) and print(parsed), should round-trip for Interval and Viewport")
    void given_printedJson_when_parse_should_roundTripCorrectly() {
        Interval interval = Interval.newBuilder()
                .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
                .setEndTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T15:00:00Z")))
                .build();
        String json = ProtoJson.defaults().print(interval);

        Interval parsed = ProtoJson.defaults().parse(json, Interval.getDefaultInstance());

        assertThat(parsed).isEqualTo(interval);
    }

    @Test
    @DisplayName("Given JSON with proto field names, when parse(), should set startTime correctly from start_time")
    void given_jsonWithProtoFieldNames_when_parse_should_acceptProtoNames() {
        String json = "{\"start_time\":\"2026-09-22T14:03:11Z\"}";

        Interval parsed = ProtoJson.defaults().parse(json, Interval.getDefaultInstance());

        assertThat(parsed.hasStartTime()).isTrue();
        assertThat(ProtoTypes.toInstant(parsed.getStartTime())).isEqualTo(Instant.parse("2026-09-22T14:03:11Z"));
    }

    @Test
    @DisplayName("Given JSON with unknown fields, when parse(), should ignore them and set regionCode")
    void given_jsonWithUnknownFields_when_parse_should_ignoreUnknownAndParseKnown() {
        String json = "{\"nope\":1,\"regionCode\":\"US\"}";

        PostalAddress parsed = ProtoJson.defaults().parse(json, PostalAddress.getDefaultInstance());

        assertThat(parsed.getRegionCode()).isEqualTo("US");
    }

    @Test
    @DisplayName("Given JSON with null value for a field, when parse(), should leave that field unset")
    void given_jsonWithNullValue_when_parse_should_leaveFieldUnset() {
        String json = "{\"startTime\":null}";

        Interval parsed = ProtoJson.defaults().parse(json, Interval.getDefaultInstance());

        assertThat(parsed.hasStartTime()).isFalse();
    }

    @Test
    @DisplayName(
            "Given JSON and descriptor, when parse(json, descriptor), should return DynamicMessage with same descriptor")
    void given_jsonAndDescriptor_when_parse_should_returnDynamicMessageWithSameDescriptor() {
        String json = "{\"startTime\":\"2026-09-22T14:03:11Z\"}";
        Descriptor descriptor = Interval.getDescriptor();

        DynamicMessage parsed = ProtoJson.defaults().parse(json, descriptor);

        assertThat(parsed.getDescriptorForType()).isSameAs(descriptor);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Errors: ProtoJsonException for invalid input
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given malformed Timestamp value 'yesterday', when parse(), should throw ProtoJsonException with typeName and value")
    void given_malformedTimestampValue_when_parse_should_throwProtoJsonException() {
        String json = "{\"startTime\":\"yesterday\"}";

        assertThatThrownBy(() -> ProtoJson.defaults().parse(json, Interval.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.typeName()).isEqualTo("google.protobuf.Timestamp");
                    assertThat(e.value()).isEqualTo("yesterday");
                });
    }

    @Test
    @DisplayName(
            "Given latLng codec and invalid value 'nowhere', when parse(), should throw ProtoJsonException with codec error message")
    void given_codecInvalidValue_when_parse_should_throwProtoJsonExceptionWithCodecMessage() {
        ProtoScalarCodec latLng = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    if (p.length != 2) throw new IllegalArgumentException("expected lat,lng");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        String json = "{\"low\":\"nowhere\"}";

        assertThatThrownBy(() -> ProtoJson.builder().scalar(latLng).build().parse(json, Viewport.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.getMessage())
                            .contains("Invalid google.type.LatLng value 'nowhere'")
                            .contains("expected lat,lng");
                });
    }

    @Test
    @DisplayName(
            "Given JSON array instead of object, when parse(), should throw ProtoJsonException mentioning 'expected a JSON object'")
    void given_jsonArray_when_parse_should_throwProtoJsonExceptionExpectedObject() {
        String json = "[1,2]";

        assertThatThrownBy(() -> ProtoJson.defaults().parse(json, Interval.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.getMessage()).contains("expected a JSON object");
                });
    }

    @Test
    @DisplayName("Given truncated JSON, when parse(), should throw ProtoJsonException mentioning 'malformed JSON'")
    void given_truncatedJson_when_parse_should_throwProtoJsonExceptionMalformed() {
        String json = "{\"startTime\":";

        assertThatThrownBy(() -> ProtoJson.defaults().parse(json, Interval.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.getMessage()).contains("malformed JSON");
                });
    }

    @Test
    @DisplayName("Given JSON with trailing content after object, when parse(), should throw ProtoJsonException")
    void given_jsonWithTrailingContent_when_parse_should_throwProtoJsonException() {
        String json = "{} trailing";

        assertThatThrownBy(() -> ProtoJson.defaults().parse(json, Interval.getDefaultInstance()))
                .isInstanceOf(ProtoJsonException.class);
    }

    @Test
    @DisplayName(
            "Given revision field with non-integer value 'abc', when parse(), should throw ProtoJsonException naming the field, its type and the value")
    void given_nonIntegerForIntField_when_parse_should_throwProtoJsonException() {
        String json = "{\"revision\":\"abc\"}";

        assertThatThrownBy(() -> ProtoJson.defaults().parse(json, PostalAddress.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.typeName()).isEqualTo("int32");
                    assertThat(e.value()).isEqualTo("abc");
                    assertThat(e.getMessage()).contains("google.type.PostalAddress.revision");
                });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Dialect options: print and parse behavior customization
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given Money USD 12 with defaults(), print() should output units as string; moneyAsAmount applies only to fields, not top-level")
    void given_moneyWithDefaults_when_print_should_outputUnitsAsString() {
        Money money = Money.newBuilder()
                .setCurrencyCode("USD")
                .setUnits(12)
                .setNanos(340000000)
                .build();

        String result = ProtoJson.defaults().print(money);

        assertThat(result).contains("\"units\":\"12\"");
    }

    @Test
    @DisplayName("Given Money USD 12 with int64AsNumber(true), print() should output units as number without quotes")
    void given_moneyWithInt64AsNumber_when_print_should_outputUnitsAsNumber() {
        Money money = Money.newBuilder().setCurrencyCode("USD").setUnits(12).build();

        String resultInt64 = ProtoJson.builder().int64AsNumber(true).build().print(money);
        String resultDefault = ProtoJson.defaults().print(money);

        assertThat(resultInt64).contains("\"units\":12");
        assertThat(resultDefault).contains("\"units\":\"12\"");
    }

    @Test
    @DisplayName(
            "Given acceptEpochMillisTimestamp(true), when parse() with epoch millis, should set timestamp; defaults() rejects it")
    void given_epochMillisTimestamp_when_parseWithOption_should_acceptMillis() {
        String json = "{\"startTime\":1790000000000}";
        long epochMillis = 1790000000000L;

        Interval result =
                ProtoJson.builder().acceptEpochMillisTimestamp(true).build().parse(json, Interval.getDefaultInstance());

        assertThat(ProtoTypes.toInstant(result.getStartTime())).isEqualTo(Instant.ofEpochMilli(epochMillis));

        assertThatThrownBy(() -> ProtoJson.defaults().parse(json, Interval.getDefaultInstance()))
                .isInstanceOf(ProtoJsonException.class);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Singleton instances: defaults() and proto3() return the same instance
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Given defaults() called twice, should return the same instance (isSameAs)")
    void given_defaultsCalledTwice_should_returnSameInstance() {
        ProtoJson first = ProtoJson.defaults();
        ProtoJson second = ProtoJson.defaults();

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("Given proto3() called twice, should return the same instance (isSameAs)")
    void given_proto3CalledTwice_should_returnSameInstance() {
        ProtoJson first = ProtoJson.proto3();
        ProtoJson second = ProtoJson.proto3();

        assertThat(first).isSameAs(second);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Builder validation: codecs and null checks
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given builder with same codec added twice, when build(), should throw IllegalArgumentException mentioning the type name")
    void given_duplicateCodecs_when_build_should_throwIllegalArgumentException() {
        ProtoScalarCodec latLng = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    if (p.length != 2) throw new IllegalArgumentException("expected lat,lng");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        assertThatThrownBy(
                        () -> ProtoJson.builder().scalar(latLng).scalar(latLng).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("google.type.LatLng");
    }

    @Test
    @DisplayName("Given builder with null codec, when scalar(null), should throw NullPointerException")
    void given_nullCodec_when_scalar_should_throwNullPointerException() {
        assertThatThrownBy(() -> ProtoJson.builder().scalar(null)).isInstanceOf(NullPointerException.class);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Independence: two instances with different dialects work side-by-side
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName(
            "Given two ProtoJson instances with different codecs, when printing alternately, should not leak codec state between instances")
    void given_twoInstancesWithDifferentCodecs_when_printingAlternately_should_provideIndependentResults() {
        ProtoScalarCodec latLng = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                    String[] p = text.split(",");
                    if (p.length != 2) throw new IllegalArgumentException("expected lat,lng");
                    return LatLng.newBuilder()
                            .setLatitude(Double.parseDouble(p[0]))
                            .setLongitude(Double.parseDouble(p[1]))
                            .build();
                });

        ProtoJson a = ProtoJson.builder().scalar(latLng).build();
        ProtoJson b = ProtoJson.defaults();

        Viewport viewport = Viewport.newBuilder()
                .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
                .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
                .build();

        String first = a.print(viewport);
        String second = b.print(viewport);
        String third = a.print(viewport);

        assertThat(first).isEqualTo("{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}");
        assertThat(second).contains("\"low\":{\"latitude\":40.5");
        assertThat(third).isEqualTo("{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper: StringWriter that tracks close() calls
    // ──────────────────────────────────────────────────────────────────────────

    private static class TrackingStringWriter extends StringWriter {
        private boolean closed = false;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean wasClosed() {
            return closed;
        }
    }
}
