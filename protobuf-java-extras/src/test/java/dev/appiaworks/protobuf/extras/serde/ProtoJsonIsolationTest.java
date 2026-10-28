package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.geo.type.Viewport;
import com.google.protobuf.Timestamp;
import com.google.type.Interval;
import com.google.type.LatLng;
import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A {@link ProtoJson} instance and the gateway's process-wide dialect share a JVM without either
 * seeing the other: the instance's dialect is in force only on the calling thread and only for
 * the length of its call.
 */
class ProtoJsonIsolationTest {

    private static final String TEXT_FORM = "{\"low\":\"40.5,-74.25\",\"high\":\"41.0,-73.5\"}";

    private static final Viewport VIEWPORT = Viewport.newBuilder()
            .setLow(LatLng.newBuilder().setLatitude(40.5).setLongitude(-74.25))
            .setHigh(LatLng.newBuilder().setLatitude(41.0).setLongitude(-73.5))
            .build();

    @AfterEach
    void restoreDefaults() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    @Test
    @DisplayName(
            "Given the gateway has a codec installed, when a ProtoJson without it prints, should not use the gateway's codec")
    void given_gatewayCodecInstalled_when_plainProtoJsonPrints_should_ignoreGatewayCodec() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(latLngCodec()));

        String json = ProtoJson.defaults().print(VIEWPORT);

        assertThat(json).startsWith("{\"low\":{\"latitude\":40.5");
        assertThat(ProtoWkt.isWkt(LatLng.getDescriptor()))
                .as("the gateway's dialect is still in force afterwards")
                .isTrue();
    }

    @Test
    @DisplayName("Given a ProtoJson with a codec, when it prints, should not leave the codec installed for the gateway")
    void given_protoJsonWithCodec_when_itPrints_should_notInstallCodecProcessWide() {
        ProtoJson json = ProtoJson.builder().scalar(latLngCodec()).build();

        assertThat(json.print(VIEWPORT)).isEqualTo(TEXT_FORM);

        assertThat(ProtoJsonMapping.isMapped(LatLng.getDescriptor())).isFalse();
        assertThat(ProtoWkt.isWkt(LatLng.getDescriptor())).isFalse();
    }

    @Test
    @DisplayName(
            "Given the gateway on the classpath, when ProtoJson reads a malformed value, should throw ProtoJsonException, not a gRPC status")
    void given_gatewayOnClasspath_when_protoJsonReadsMalformedValue_should_throwProtoJsonException() {
        assertThatThrownBy(() ->
                        ProtoJson.defaults().parse("{\"startTime\":\"yesterday\"}", Interval.getDefaultInstance()))
                .isInstanceOfSatisfying(ProtoJsonException.class, e -> {
                    assertThat(e.typeName()).isEqualTo("google.protobuf.Timestamp");
                    assertThat(e.value()).isEqualTo("yesterday");
                });
    }

    @Test
    @DisplayName(
            "Given a ProtoJson call has run, when the gateway's own serde reads a malformed value, should still throw INVALID_ARGUMENT")
    void given_protoJsonCallHasRun_when_gatewaySerdeReadsMalformedValue_should_throwInvalidArgument() {
        ProtoJson.defaults().print(VIEWPORT);

        assertThatThrownBy(() -> ProtoWkt.fromJava("yesterday", Timestamp.getDescriptor()))
                .isInstanceOf(ProtoJsonException.class);
    }

    @Test
    @DisplayName(
            "Given a codec that itself uses another ProtoJson, when the outer instance prints, should keep its own dialect for the fields that follow")
    void given_codecUsingAnotherProtoJson_when_outerPrints_should_restoreOuterDialect() {
        ProtoScalarCodec nesting = ProtoScalarCodec.ofString(
                LatLng.getDefaultInstance(),
                ll -> {
                    // Runs in the outer call; the inner instance has no LatLng codec.
                    String inner = ProtoJson.proto3().print(ll);
                    assertThat(inner).startsWith("{\"latitude\":");
                    return ll.getLatitude() + "," + ll.getLongitude();
                },
                text -> LatLng.getDefaultInstance());
        ProtoJson outer = ProtoJson.builder().scalar(nesting).build();

        // "high" is written after the nested call made while writing "low".
        assertThat(outer.print(VIEWPORT)).isEqualTo(TEXT_FORM);
    }

    @Test
    @DisplayName(
            "Given two ProtoJson instances with different dialects used from many threads at once, when they print, should each keep their own form")
    void given_twoDialectsOnManyThreads_when_printing_should_neverMix() throws Exception {
        ProtoJson withCodec = ProtoJson.builder().scalar(latLngCodec()).build();
        ProtoJson plain = ProtoJson.defaults();
        String plainForm = plain.print(VIEWPORT);
        assertThat(plainForm).isNotEqualTo(TEXT_FORM);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                boolean useCodec = t % 2 == 0;
                Callable<Integer> task = () -> {
                    int wrong = 0;
                    for (int i = 0; i < 2_000; i++) {
                        String json = (useCodec ? withCodec : plain).print(VIEWPORT);
                        if (!json.equals(useCodec ? TEXT_FORM : plainForm)) wrong++;
                    }
                    return wrong;
                };
                results.add(pool.submit(task));
            }
            int wrong = 0;
            for (Future<Integer> result : results) wrong += result.get();
            assertThat(wrong).isZero();
        } finally {
            pool.shutdownNow();
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
