package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import com.google.type.LatLng;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.serde.MalformedValueTranslator;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.ProtoWkt;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for GrpcMalformedValueTranslator.
 * Tests verify that ProtoJsonException values are correctly translated to gRPC StatusRuntimeException.
 */
class GrpcMalformedValueTranslatorTest {

    @Test
    @DisplayName(
            "Given ProtoJsonException with cause, when translate, should return StatusRuntimeException with INVALID_ARGUMENT and cause")
    void given_protoJsonExceptionWithCause_when_translate_should_returnStatusRuntimeExceptionWithCause() {
        GrpcMalformedValueTranslator translator = new GrpcMalformedValueTranslator();
        Throwable cause = new IllegalArgumentException("original cause");
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", "2026-02-30", "bad day", cause);

        RuntimeException result = translator.translate(exception);

        assertThat(result).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException sre = (StatusRuntimeException) result;
        assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(sre.getStatus().getDescription()).isEqualTo("Invalid google.type.Date value '2026-02-30': bad day");
        assertThat(sre.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName(
            "Given ProtoJsonException with null cause, when translate, should return StatusRuntimeException with null cause")
    void given_protoJsonExceptionWithNullCause_when_translate_should_returnStatusRuntimeExceptionWithNullCause() {
        GrpcMalformedValueTranslator translator = new GrpcMalformedValueTranslator();
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", "2026-02-30", "bad day", null);

        RuntimeException result = translator.translate(exception);

        assertThat(result).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException sre = (StatusRuntimeException) result;
        assertThat(sre.getCause()).isNull();
    }

    @Test
    @DisplayName("Given StatusRuntimeException, when isTranslated, should return true")
    void given_statusRuntimeException_when_isTranslated_should_returnTrue() {
        GrpcMalformedValueTranslator translator = new GrpcMalformedValueTranslator();
        StatusRuntimeException exception = Status.INVALID_ARGUMENT.asRuntimeException();

        assertThat(translator.isTranslated(exception)).isTrue();
    }

    @Test
    @DisplayName("Given IllegalArgumentException, when isTranslated, should return false")
    void given_illegalArgumentException_when_isTranslated_should_returnFalse() {
        GrpcMalformedValueTranslator translator = new GrpcMalformedValueTranslator();
        IllegalArgumentException exception = new IllegalArgumentException("x");

        assertThat(translator.isTranslated(exception)).isFalse();
    }

    @Test
    @DisplayName("Given ProtoJsonException, when isTranslated, should return false")
    void given_protoJsonException_when_isTranslated_should_returnFalse() {
        GrpcMalformedValueTranslator translator = new GrpcMalformedValueTranslator();
        ProtoJsonException exception = new ProtoJsonException("google.type.Date", "2026-02-30", "bad day", null);

        assertThat(translator.isTranslated(exception)).isFalse();
    }

    @Test
    @DisplayName(
            "Given ServiceLoader, when load MalformedValueTranslator, should yield exactly one GrpcMalformedValueTranslator")
    void given_serviceLoader_when_loadMalformedValueTranslator_should_yieldExactlyOneGrpcTranslator() {
        int count = 0;
        GrpcMalformedValueTranslator found = null;

        for (MalformedValueTranslator translator : ServiceLoader.load(MalformedValueTranslator.class)) {
            count++;
            found = (GrpcMalformedValueTranslator) translator;
        }

        assertThat(count).isEqualTo(1);
        assertThat(found).isInstanceOf(GrpcMalformedValueTranslator.class);
    }

    @Test
    @DisplayName(
            "Given ProtoWkt with invalid Timestamp, when fromJava, should throw StatusRuntimeException INVALID_ARGUMENT with type and value in description")
    void given_protoWktWithInvalidTimestamp_when_fromJava_should_throwStatusRuntimeExceptionInvalidArgument() {
        assertThatThrownBy(() -> ProtoWkt.fromJava("yesterday", com.google.protobuf.Timestamp.getDescriptor()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting("status")
                .extracting("code")
                .isEqualTo(Status.Code.INVALID_ARGUMENT);

        assertThatThrownBy(() -> ProtoWkt.fromJava("yesterday", com.google.protobuf.Timestamp.getDescriptor()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting("status")
                .extracting("description")
                .asString()
                .contains("google.protobuf.Timestamp")
                .contains("yesterday");
    }

    @AfterEach
    void resetMapping() {
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS);
    }

    @Test
    @DisplayName(
            "Given a codec that throws StatusRuntimeException FAILED_PRECONDITION, when ProtoWkt.fromJava, should rethrow it untranslated")
    void given_codecThrowsStatusRuntimeException_when_fromJava_should_rethrowIt() {
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
                throw Status.FAILED_PRECONDITION.withDescription("Not allowed").asRuntimeException();
            }
        };
        ProtoJsonMapping.install(ProtoJsonMapping.DEFAULTS, List.of(failingCodec));

        assertThatThrownBy(() -> ProtoWkt.fromJava("any value", LatLng.getDescriptor()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION));
    }
}
