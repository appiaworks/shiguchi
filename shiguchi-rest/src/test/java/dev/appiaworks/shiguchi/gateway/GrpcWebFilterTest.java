package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Unit tests for gRPC-Web static helper methods in {@link GrpcWebFilter}.
 */
@DisplayName("gRPC-Web filter helpers")
class GrpcWebFilterTest {

    @Test
    @DisplayName("GIVEN a timeout string with minutes WHEN parsed SHOULD return Duration in minutes")
    void given_timeoutMinutes_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("100m");

        assertThat(duration).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    @DisplayName("GIVEN a timeout string with seconds WHEN parsed SHOULD return Duration in seconds")
    void given_timeoutSeconds_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("5S");

        assertThat(duration).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN a timeout string with hours WHEN parsed SHOULD return Duration in hours")
    void given_timeoutHours_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("2H");

        assertThat(duration).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("GIVEN a timeout string with microseconds WHEN parsed SHOULD return Duration in nanos")
    void given_timeoutMicros_when_parsed_should_returnDuration() {
        Duration duration = GrpcWebFilter.parseTimeout("30u");

        assertThat(duration).isEqualTo(Duration.ofNanos(30_000));
    }

    @Test
    @DisplayName("GIVEN an invalid timeout unit WHEN parsed SHOULD return null")
    void given_invalidTimeoutUnit_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout("100x");

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a non-numeric timeout value WHEN parsed SHOULD return null")
    void given_nonNumericTimeout_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout("abcm");

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a null timeout value WHEN parsed SHOULD return null")
    void given_nullTimeout_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout(null);

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a malformed timeout string WHEN parsed SHOULD return null")
    void given_malformedTimeout_when_parsed_should_returnNull() {
        Duration duration = GrpcWebFilter.parseTimeout("invalid");

        assertThat(duration).isNull();
    }

    @Test
    @DisplayName("GIVEN a method path with package.Service/Method and no prefix WHEN parsed SHOULD split into parts")
    void given_methodPathWithNoPrefix_when_parsed_should_splitParts() {
        String[] parts = GrpcWebFilter.methodPath("/pkg.Svc/Method", "");

        assertThat(parts).hasSize(2).containsExactly("pkg.Svc", "Method");
    }

    @Test
    @DisplayName("GIVEN a method path with prefix WHEN parsed SHOULD strip prefix and split")
    void given_methodPathWithPrefix_when_parsed_should_stripAndSplit() {
        String[] parts = GrpcWebFilter.methodPath("/grpc-web/pkg.Svc/Method", "/grpc-web");

        assertThat(parts).hasSize(2).containsExactly("pkg.Svc", "Method");
    }

    @Test
    @DisplayName("GIVEN a method path with three segments (not two) WHEN parsed SHOULD return null")
    void given_methodPathThreeSegments_when_parsed_should_returnNull() {
        String[] parts = GrpcWebFilter.methodPath("/pkg.Svc/Method/Extra", "");

        assertThat(parts).isNull();
    }

    @Test
    @DisplayName("GIVEN a method path not starting with prefix WHEN parsed SHOULD return null")
    void given_methodPathMissingPrefix_when_parsed_should_returnNull() {
        String[] parts = GrpcWebFilter.methodPath("/pkg.Svc/Method", "/grpc-web");

        assertThat(parts).isNull();
    }

    @Test
    @DisplayName("GIVEN an empty service or method segment WHEN parsed SHOULD return null")
    void given_emptySegment_when_parsed_should_returnNull() {
        String[] parts = GrpcWebFilter.methodPath("//Method", "");

        assertThat(parts).isNull();
    }

    @Test
    @DisplayName("GIVEN a prefix with trailing slashes WHEN normalized SHOULD remove trailing slashes")
    void given_prefixWithTrailingSlashes_when_normalized_should_removeSlashes() {
        String normalized = GrpcWebFilter.normalizePrefix("/grpc-web/");

        assertThat(normalized).isEqualTo("/grpc-web");
    }

    @Test
    @DisplayName("GIVEN a prefix with multiple trailing slashes WHEN normalized SHOULD remove all")
    void given_prefixWithMultipleSlashes_when_normalized_should_removeAll() {
        String normalized = GrpcWebFilter.normalizePrefix("/grpc-web///");

        assertThat(normalized).isEqualTo("/grpc-web");
    }

    @Test
    @DisplayName("GIVEN a null prefix WHEN normalized SHOULD return empty string")
    void given_nullPrefix_when_normalized_should_returnEmpty() {
        String normalized = GrpcWebFilter.normalizePrefix(null);

        assertThat(normalized).isEmpty();
    }

    @Test
    @DisplayName("GIVEN application/grpc-web+proto content type WHEN checked SHOULD return true")
    void given_grpcWebProtoContentType_when_checked_should_returnTrue() {
        MediaType contentType = MediaType.parseMediaType("application/grpc-web+proto");

        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(contentType);

        assertThat(isGrpcWeb).isTrue();
    }

    @Test
    @DisplayName("GIVEN application/grpc-web-text+proto content type WHEN checked SHOULD return true")
    void given_grpcWebTextProtoContentType_when_checked_should_returnTrue() {
        MediaType contentType = MediaType.parseMediaType("application/grpc-web-text+proto");

        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(contentType);

        assertThat(isGrpcWeb).isTrue();
    }

    @Test
    @DisplayName("GIVEN application/json content type WHEN checked SHOULD return false")
    void given_jsonContentType_when_checked_should_returnFalse() {
        MediaType contentType = MediaType.APPLICATION_JSON;

        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(contentType);

        assertThat(isGrpcWeb).isFalse();
    }

    @Test
    @DisplayName("GIVEN null content type WHEN checked SHOULD return false")
    void given_nullContentType_when_checked_should_returnFalse() {
        boolean isGrpcWeb = GrpcWebFilter.isGrpcWeb(null);

        assertThat(isGrpcWeb).isFalse();
    }
}
