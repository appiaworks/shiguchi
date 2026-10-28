package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GatewayErrorDetails")
class GatewayErrorDetailsTest {

    private static final BadRequest BAD_REQUEST = BadRequest.newBuilder()
            .addFieldViolations(
                    BadRequest.FieldViolation.newBuilder().setField("email").setDescription("must be a valid address"))
            .build();

    private static StatusRuntimeException failure(Any... details) {
        com.google.rpc.Status rich = com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage("bad input")
                .addAllDetails(List.of(details))
                .build();
        return StatusProto.toStatusRuntimeException(rich);
    }

    @Test
    @DisplayName("GIVEN trailers carrying a BadRequest WHEN decoded SHOULD expose it typed and as proto3 JSON")
    void given_badRequestInTrailers_when_decoded_should_exposeTypedAndJson() {
        StatusRuntimeException ex = failure(Any.pack(BAD_REQUEST));

        GatewayErrorDetails details = GatewayErrorDetails.from(ex.getStatus(), ex.getTrailers(), null);

        assertThat(details.isEmpty()).isFalse();
        assertThat(details.badRequest()).contains(BAD_REQUEST);
        assertThat(details.retryInfo()).isEmpty();
        List<Map<String, Object>> json = details.asJson();
        assertThat(json).hasSize(1);
        assertThat(json.get(0)).containsEntry("@type", "type.googleapis.com/google.rpc.BadRequest");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) json.get(0).get("fieldViolations");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0))
                .containsEntry("field", "email")
                .containsEntry("description", "must be a valid address");
    }

    @Test
    @DisplayName("GIVEN a RetryInfo detail WHEN asked for the retry delay SHOULD return it as a Duration")
    void given_retryInfo_when_retryDelay_should_returnDuration() {
        RetryInfo retry = RetryInfo.newBuilder()
                .setRetryDelay(
                        com.google.protobuf.Duration.newBuilder().setSeconds(2).setNanos(500_000_000))
                .build();
        StatusRuntimeException ex = failure(
                Any.pack(retry),
                Any.pack(ErrorInfo.newBuilder().setReason("QUOTA").build()));

        GatewayErrorDetails details = GatewayErrorDetails.from(ex, null);

        assertThat(details.retryDelay()).contains(Duration.ofMillis(2500));
        assertThat(details.errorInfo().map(ErrorInfo::getReason)).contains("QUOTA");
        assertThat(details.asJson())
                .extracting(m -> m.get("@type"))
                .containsExactly(
                        "type.googleapis.com/google.rpc.RetryInfo", "type.googleapis.com/google.rpc.ErrorInfo");
    }

    @Test
    @DisplayName(
            "GIVEN a detail of a type the gateway does not know WHEN printed SHOULD fall back to @type plus base64 value")
    void given_unknownDetailType_when_printed_should_fallBackToOpaqueShape() {
        Any unknown = Any.newBuilder()
                .setTypeUrl("type.googleapis.com/acme.Custom")
                .setValue(com.google.protobuf.ByteString.copyFrom(new byte[] {8, 1}))
                .build();
        StatusRuntimeException ex = failure(unknown);

        List<Map<String, Object>> json = GatewayErrorDetails.from(ex, null).asJson();

        assertThat(json).hasSize(1);
        assertThat(json.get(0))
                .containsEntry("@type", "type.googleapis.com/acme.Custom")
                .containsEntry("value", java.util.Base64.getEncoder().encodeToString(new byte[] {8, 1}));
    }

    @Test
    @DisplayName(
            "GIVEN a detail of a service-specific type WHEN the service's type registry knows it SHOULD print it as JSON")
    void given_serviceSpecificDetail_when_registryKnowsIt_should_printAsJson() {
        // StringValue stands in for a backend's own message type: not a google.rpc type, but in the registry.
        StatusRuntimeException ex = failure(Any.pack(StringValue.of("ctx")));
        JsonFormat.TypeRegistry serviceTypes = JsonFormat.TypeRegistry.newBuilder()
                .add(StringValue.getDescriptor())
                .build();

        List<Map<String, Object>> json =
                GatewayErrorDetails.from(ex, serviceTypes).asJson();

        assertThat(json.get(0))
                .containsEntry("@type", "type.googleapis.com/google.protobuf.StringValue")
                .containsEntry("value", "ctx");
    }

    @Test
    @DisplayName("GIVEN a failure without trailers or without details WHEN decoded SHOULD be the empty instance")
    void given_noTrailersOrNoDetails_when_decoded_should_beEmpty() {
        assertThat(GatewayErrorDetails.from(Status.NOT_FOUND, null, null).isEmpty())
                .isTrue();
        assertThat(GatewayErrorDetails.from(Status.NOT_FOUND, new Metadata(), null)
                        .isEmpty())
                .isTrue();
        assertThat(GatewayErrorDetails.from(Status.NOT_FOUND.asRuntimeException(), null)
                        .asJson())
                .isEmpty();
        assertThat(GatewayErrorDetails.none().retryDelay()).isEmpty();
    }
}
