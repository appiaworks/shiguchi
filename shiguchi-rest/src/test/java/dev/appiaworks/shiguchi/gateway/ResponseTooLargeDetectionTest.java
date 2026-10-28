package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import com.google.rpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ShiguchiFilter#isResponseTooLarge(StatusRuntimeException)}: detecting
 * when a gRPC error means a backend's response was too big to relay (502) rather than a
 * backend-initiated backpressure (429).
 */
@DisplayName("GIVEN a StatusRuntimeException WHEN testing if response too large SHOULD detect correctly")
class ResponseTooLargeDetectionTest {

    @Test
    @DisplayName(
            "GIVEN RESOURCE_EXHAUSTED with description 'message exceeds maximum size' WHEN checked SHOULD return true")
    void given_resourceExhaustedWithExceedsMessageDescription_when_checked_should_returnTrue() {
        StatusRuntimeException ex = io.grpc.Status.RESOURCE_EXHAUSTED
                .withDescription("gRPC message exceeds maximum size 256: 500")
                .asRuntimeException();

        assertThat(ShiguchiFilter.isResponseTooLarge(ex)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN RESOURCE_EXHAUSTED with ErrorInfo reason RESPONSE_TOO_LARGE and domain shiguchi WHEN checked SHOULD return true")
    void given_resourceExhaustedWithValidErrorInfo_when_checked_should_returnTrue() {
        ErrorInfo info = ErrorInfo.newBuilder()
                .setReason("RESPONSE_TOO_LARGE")
                .setDomain("shiguchi")
                .build();
        Status status = Status.newBuilder()
                .setCode(io.grpc.Status.Code.RESOURCE_EXHAUSTED.value())
                .setMessage("Response body exceeds the limit")
                .addDetails(Any.pack(info))
                .build();
        StatusRuntimeException ex = StatusProto.toStatusRuntimeException(status);

        assertThat(ShiguchiFilter.isResponseTooLarge(ex)).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN RESOURCE_EXHAUSTED with ErrorInfo reason RESPONSE_TOO_LARGE but domain 'acme.com' WHEN checked SHOULD return false")
    void given_resourceExhaustedWithWrongDomain_when_checked_should_returnFalse() {
        ErrorInfo info = ErrorInfo.newBuilder()
                .setReason("RESPONSE_TOO_LARGE")
                .setDomain("acme.com")
                .build();
        Status status = Status.newBuilder()
                .setCode(io.grpc.Status.Code.RESOURCE_EXHAUSTED.value())
                .setMessage("Some error")
                .addDetails(Any.pack(info))
                .build();
        StatusRuntimeException ex = StatusProto.toStatusRuntimeException(status);

        assertThat(ShiguchiFilter.isResponseTooLarge(ex)).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN RESOURCE_EXHAUSTED with ErrorInfo reason 'QUOTA_EXCEEDED' but correct domain WHEN checked SHOULD return false")
    void given_resourceExhaustedWithWrongReason_when_checked_should_returnFalse() {
        ErrorInfo info = ErrorInfo.newBuilder()
                .setReason("QUOTA_EXCEEDED")
                .setDomain("shiguchi")
                .build();
        Status status = Status.newBuilder()
                .setCode(io.grpc.Status.Code.RESOURCE_EXHAUSTED.value())
                .setMessage("Quota exceeded")
                .addDetails(Any.pack(info))
                .build();
        StatusRuntimeException ex = StatusProto.toStatusRuntimeException(status);

        assertThat(ShiguchiFilter.isResponseTooLarge(ex)).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN RESOURCE_EXHAUSTED with description 'quota exceeded' and no details WHEN checked SHOULD return false")
    void given_resourceExhaustedWithQuotaExceededDescription_when_checked_should_returnFalse() {
        StatusRuntimeException ex = io.grpc.Status.RESOURCE_EXHAUSTED
                .withDescription("quota exceeded; please retry after 5 seconds")
                .asRuntimeException();

        assertThat(ShiguchiFilter.isResponseTooLarge(ex)).isFalse();
    }

    @Test
    @DisplayName("GIVEN UNAVAILABLE with description 'message exceeds maximum size' WHEN checked SHOULD return false")
    void given_unavailableWithExceedsMessageDescription_when_checked_should_returnFalse() {
        StatusRuntimeException ex = io.grpc.Status.UNAVAILABLE
                .withDescription("message exceeds maximum size")
                .asRuntimeException();

        assertThat(ShiguchiFilter.isResponseTooLarge(ex)).isFalse();
    }

    @Test
    @DisplayName("GIVEN RESOURCE_EXHAUSTED with no description and no details WHEN checked SHOULD return false")
    void given_resourceExhaustedWithNoDetails_when_checked_should_returnFalse() {
        StatusRuntimeException ex = io.grpc.Status.RESOURCE_EXHAUSTED.asRuntimeException();

        assertThat(ShiguchiFilter.isResponseTooLarge(ex)).isFalse();
    }
}
