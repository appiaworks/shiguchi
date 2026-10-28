package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.geo.type.Viewport;
import com.google.type.Interval;
import com.google.type.LatLng;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The code docs/proto-serde-standalone.md shows, run as written. If this test changes, that page
 * changes with it.
 *
 * <p>This module's test classpath has no Spring and no gRPC, so the test also proves the library
 * runs on protobuf and Jackson's streaming API alone.
 */
class StandaloneSerdeUsageTest {

    private static final ProtoScalarCodec LAT_LNG = ProtoScalarCodec.ofString(
            LatLng.getDefaultInstance(), ll -> ll.getLatitude() + "," + ll.getLongitude(), text -> {
                String[] parts = text.split(",");
                if (parts.length != 2) throw new IllegalArgumentException("expected lat,lng");
                return LatLng.newBuilder()
                        .setLatitude(Double.parseDouble(parts[0]))
                        .setLongitude(Double.parseDouble(parts[1]))
                        .build();
            });

    @Test
    @DisplayName(
            "Given a message with well-known types, when printed and parsed with the default dialect, should use proto3 JSON forms and round-trip")
    void given_wellKnownTypes_when_roundTripped_should_keepProto3Forms() {
        ProtoJson json = ProtoJson.defaults();
        Interval interval = Interval.newBuilder()
                .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
                .setEndTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T15:00:00Z")))
                .build();

        String text = json.print(interval);

        assertThat(text).isEqualTo("{\"startTime\":\"2026-09-22T14:03:11Z\",\"endTime\":\"2026-09-22T15:00:00Z\"}");
        assertThat(json.parse(text, Interval.getDefaultInstance())).isEqualTo(interval);
    }

    @Test
    @DisplayName(
            "Given a ProtoJson built with a scalar codec, when a message with that type is printed and parsed, should use the codec's text")
    void given_scalarCodec_when_roundTripped_should_useCodecText() {
        ProtoJson json = ProtoJson.builder().scalar(LAT_LNG).build();
        Viewport viewport = Viewport.newBuilder()
                .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
                .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
                .build();

        String text = json.print(viewport);

        assertThat(text).isEqualTo("{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}");
        assertThat(json.parse(text, Viewport.getDefaultInstance())).isEqualTo(viewport);
    }

    @Test
    @DisplayName(
            "Given malformed text for a codec-mapped field, when parsed, should throw ProtoJsonException naming the type and value")
    void given_malformedCodecText_when_parsed_should_throwProtoJsonException() {
        ProtoJson json = ProtoJson.builder().scalar(LAT_LNG).build();

        assertThatThrownBy(() -> json.parse("{\"low\":\"nowhere\"}", Viewport.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.typeName()).isEqualTo("google.type.LatLng");
                    assertThat(e.value()).isEqualTo("nowhere");
                    assertThat(e).hasMessage("Invalid google.type.LatLng value 'nowhere': expected lat,lng");
                });
    }

    @Test
    @DisplayName("Given a malformed Timestamp, when parsed, should throw ProtoJsonException rather than drop the field")
    void given_malformedTimestamp_when_parsed_should_throwProtoJsonException() {
        assertThatThrownBy(() ->
                        ProtoJson.defaults().parse("{\"startTime\":\"yesterday\"}", Interval.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.typeName()).isEqualTo("google.protobuf.Timestamp");
                    assertThat(e.value()).isEqualTo("yesterday");
                });
    }

    @Test
    @DisplayName("Given print options on the builder, when printed, should change the layout only")
    void given_printOptions_when_printed_should_changeLayout() {
        ProtoJson json = ProtoJson.builder()
                .preserveProtoFieldNames(true)
                .omitDefaultValues(true)
                .build();
        Interval interval = Interval.newBuilder()
                .setStartTime(ProtoTypes.timestamp(Instant.parse("2026-09-22T14:03:11Z")))
                .build();

        assertThat(json.print(interval)).isEqualTo("{\"start_time\":\"2026-09-22T14:03:11Z\"}");
    }

    @Test
    @DisplayName("Given no gateway on the classpath, when the serde loads, should find neither gRPC nor Spring")
    void given_extrasAlone_when_loaded_should_haveNoGrpcOrSpring() {
        assertThatThrownBy(() -> Class.forName("io.grpc.Status")).isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("org.springframework.context.ApplicationContext"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
