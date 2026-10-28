package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * BDD-style tests for DefaultGrpcStatusMapper.
 *
 * Tests verify that gRPC status codes are mapped to the correct HTTP status codes
 * following the standard transcoding convention used by grpc-gateway and Cloud Endpoints.
 */
class DefaultGrpcStatusMapperTest {

    private final DefaultGrpcStatusMapper mapper = new DefaultGrpcStatusMapper();

    @Test
    @DisplayName("Given gRPC status OK, when toHttpStatus is called, should return HTTP 200")
    void given_grpcOk_when_toHttpStatus_should_return200() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.OK);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Given gRPC status INVALID_ARGUMENT, when toHttpStatus is called, should return HTTP 400")
    void given_grpcInvalidArgument_when_toHttpStatus_should_return400() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.INVALID_ARGUMENT);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Given gRPC status NOT_FOUND, when toHttpStatus is called, should return HTTP 404")
    void given_grpcNotFound_when_toHttpStatus_should_return404() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.NOT_FOUND);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Given gRPC status DEADLINE_EXCEEDED, when toHttpStatus is called, should return HTTP 504")
    void given_grpcDeadlineExceeded_when_toHttpStatus_should_return504() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.DEADLINE_EXCEEDED);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("Given gRPC status UNAVAILABLE, when toHttpStatus is called, should return HTTP 503")
    void given_grpcUnavailable_when_toHttpStatus_should_return503() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.UNAVAILABLE);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Given gRPC status UNAUTHENTICATED, when toHttpStatus is called, should return HTTP 401")
    void given_grpcUnauthenticated_when_toHttpStatus_should_return401() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.UNAUTHENTICATED);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Given gRPC status PERMISSION_DENIED, when toHttpStatus is called, should return HTTP 403")
    void given_grpcPermissionDenied_when_toHttpStatus_should_return403() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.PERMISSION_DENIED);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Given gRPC status UNIMPLEMENTED, when toHttpStatus is called, should return HTTP 501")
    void given_grpcUnimplemented_when_toHttpStatus_should_return501() {
        // Act
        HttpStatus result = mapper.toHttpStatus(Status.UNIMPLEMENTED);

        // Assert
        assertThat(result).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
    }
}
