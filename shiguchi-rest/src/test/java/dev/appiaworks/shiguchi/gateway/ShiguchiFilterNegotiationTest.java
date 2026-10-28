package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.rest.GrpcPathMatcher;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.GrpcResponsePaginationExtractor;
import dev.appiaworks.shiguchi.rest.GrpcStatusMapper;
import dev.appiaworks.shiguchi.rest.MergeDispatcher;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.rest.RestBodyCodec;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * BDD-style tests for ShiguchiFilter content negotiation (negotiate method).
 *
 * <p>Tests verify that:
 *
 * <ul>
 *   <li>Without codecs, negotiate returns JSON on both request and response sides
 *   <li>Content-Type application/json and +json subtypes are recognized as JSON
 *   <li>A request Content-Type matching a codec's media type selects that codec
 *   <li>An unsupported request Content-Type throws UnsupportedMediaType
 *   <li>Accept header with highest quality-weighted codec is selected
 *   <li>JSON and wildcard Accept values select JSON (null codec)
 *   <li>Accept header with no supported media types throws NotAcceptable
 *   <li>Quality value 0 means reject and is skipped
 *   <li>Case-insensitive media type matching
 *   <li>Vendor-prefixed avro/* and protobuf/* types trigger appropriate errors
 * </ul>
 */
@DisplayName("ShiguchiFilter content negotiation")
class ShiguchiFilterNegotiationTest {

    private ShiguchiFilter filterWithNoCodecs;
    private ShiguchiFilter filterWithAvroCodec;
    private RestBodyCodec mockAvroCodec;
    private RestBodyCodec mockProtobufCodec;

    @BeforeEach
    void setUp() {
        // Create a filter with no codecs (JSON-only)
        filterWithNoCodecs = new ShiguchiFilter(
                new GrpcRouteRegistry(),
                new GrpcPathMatcher(),
                mock(ReactiveGrpcDispatcher.class),
                mock(dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator.class),
                mock(dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer.class),
                mock(GrpcStatusMapper.class),
                mock(GrpcResponsePaginationExtractor.class),
                mock(dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer.class),
                mock(MergeDispatcher.class),
                mock(GrpcRequestBuilder.class),
                mock(SerdeSelector.class),
                new GatewayProperties(),
                List.of());

        // Create mock Avro codec
        mockAvroCodec = mock(RestBodyCodec.class);
        org.mockito.Mockito.when(mockAvroCodec.supports(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String mediaType = invocation.getArgument(0);
                    return mediaType != null
                            && (mediaType.equals("avro/binary")
                                    || mediaType.equals("application/vnd.apache.avro+binary")
                                    || mediaType.equals("application/avro")
                                    || mediaType.equals("application/avro+binary"));
                });
        org.mockito.Mockito.when(mockAvroCodec.mediaType()).thenReturn("avro/binary");

        // Create mock Protobuf codec
        mockProtobufCodec = mock(RestBodyCodec.class);
        org.mockito.Mockito.when(mockProtobufCodec.supports(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String mediaType = invocation.getArgument(0);
                    return mediaType != null
                            && (mediaType.equals("application/protobuf")
                                    || mediaType.equals("application/vnd.google.protobuf"));
                });
        org.mockito.Mockito.when(mockProtobufCodec.mediaType()).thenReturn("application/protobuf");

        // Create a filter with Avro and Protobuf codecs
        filterWithAvroCodec = new ShiguchiFilter(
                new GrpcRouteRegistry(),
                new GrpcPathMatcher(),
                mock(ReactiveGrpcDispatcher.class),
                mock(dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator.class),
                mock(dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer.class),
                mock(GrpcStatusMapper.class),
                mock(GrpcResponsePaginationExtractor.class),
                mock(dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer.class),
                mock(MergeDispatcher.class),
                mock(GrpcRequestBuilder.class),
                mock(SerdeSelector.class),
                new GatewayProperties(),
                List.of(mockAvroCodec, mockProtobufCodec));
    }

    @Test
    @DisplayName("GIVEN filter with no codecs and any headers WHEN negotiate called SHOULD return JSON (null codecs)")
    void given_noCodcs_when_negotiate_should_returnJsonOnly() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "avro/binary");
        headers.set(HttpHeaders.ACCEPT, "avro/binary");

        // Act
        var negotiated = filterWithNoCodecs.negotiate(headers);

        // Assert: JSON-only, regardless of headers
        assertThat(negotiated.requestCodec()).isNull();
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN application/json Content-Type WHEN negotiate called SHOULD select JSON request codec (null)")
    void given_jsonContentType_when_negotiate_should_selectJsonRequestCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert
        assertThat(negotiated.requestCodec()).isNull();
        // No Accept header, so response is also JSON
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN application/json+custom Content-Type WHEN negotiate called SHOULD recognize +json as JSON")
    void given_plusJsonContentType_when_negotiate_should_recognizePlusJsonAsJson() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/vnd.custom+json");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: +json suffix indicates JSON, so null request codec
        assertThat(negotiated.requestCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN avro/binary Content-Type WHEN negotiate called SHOULD select Avro request codec")
    void given_avroBinaryContentType_when_negotiate_should_selectAvroRequestCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "avro/binary");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert
        assertThat(negotiated.requestCodec()).isSameAs(mockAvroCodec);
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN AVRO/BINARY Content-Type (uppercase) WHEN negotiate called SHOULD match case-insensitively")
    void given_uppercaseAvroContentType_when_negotiate_should_matchCaseInsensitively() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "AVRO/BINARY");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: case-insensitive matching
        assertThat(negotiated.requestCodec()).isSameAs(mockAvroCodec);
    }

    @Test
    @DisplayName("GIVEN application/vnd.apache.avro+binary Content-Type WHEN negotiate called SHOULD select Avro codec")
    void given_avroVendorContentType_when_negotiate_should_selectAvroCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/vnd.apache.avro+binary");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert
        assertThat(negotiated.requestCodec()).isSameAs(mockAvroCodec);
    }

    @Test
    @DisplayName(
            "GIVEN unsupported Content-Type like text/plain WHEN negotiate called SHOULD NOT throw (no error for non-avro/protobuf types)")
    void given_unsupportedContentType_when_negotiate_should_returnNull() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: unknown types are silently ignored and JSON is used
        assertThat(negotiated.requestCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN Content-Type starting with avro/ but not supported WHEN negotiate called SHOULD throw UnsupportedMediaType")
    void given_unsupportedAvroContentType_when_negotiate_should_throwUnsupportedMediaType() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "avro/unsupported");

        // Act & Assert
        assertThatThrownBy(() -> filterWithAvroCodec.negotiate(headers))
                .isInstanceOf(ShiguchiFilter.UnsupportedMediaType.class)
                .hasMessageContaining("Unsupported request Content-Type");
    }

    @Test
    @DisplayName(
            "GIVEN Content-Type application/avro+something unsupported WHEN negotiate called SHOULD throw UnsupportedMediaType")
    void given_unsupportedApplicationAvroContentType_when_negotiate_should_throwUnsupportedMediaType() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/avro+unsupported");

        // Act & Assert
        assertThatThrownBy(() -> filterWithAvroCodec.negotiate(headers))
                .isInstanceOf(ShiguchiFilter.UnsupportedMediaType.class)
                .hasMessageContaining("Unsupported request Content-Type");
    }

    @Test
    @DisplayName(
            "GIVEN Content-Type application/protobuf but only Avro codec available WHEN negotiate called SHOULD throw UnsupportedMediaType")
    void given_protobufContentTypeButNoCodec_when_negotiate_should_throwUnsupportedMediaType() {
        // Create a filter with ONLY Avro codec (no protobuf)
        ShiguchiFilter filterWithOnlyAvro = new ShiguchiFilter(
                new GrpcRouteRegistry(),
                new GrpcPathMatcher(),
                mock(ReactiveGrpcDispatcher.class),
                mock(dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator.class),
                mock(dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer.class),
                mock(GrpcStatusMapper.class),
                mock(GrpcResponsePaginationExtractor.class),
                mock(dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer.class),
                mock(MergeDispatcher.class),
                mock(GrpcRequestBuilder.class),
                mock(SerdeSelector.class),
                new GatewayProperties(),
                List.of(mockAvroCodec));

        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/protobuf");

        // Act & Assert
        assertThatThrownBy(() -> filterWithOnlyAvro.negotiate(headers))
                .isInstanceOf(ShiguchiFilter.UnsupportedMediaType.class);
    }

    @Test
    @DisplayName("GIVEN Accept: application/json WHEN negotiate called SHOULD select JSON response codec (null)")
    void given_jsonAccept_when_negotiate_should_selectJsonResponseCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert
        assertThat(negotiated.requestCodec()).isNull();
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN Accept: avro/binary WHEN negotiate called SHOULD select Avro response codec")
    void given_avroAccept_when_negotiate_should_selectAvroResponseCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "avro/binary");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert
        assertThat(negotiated.requestCodec()).isNull();
        assertThat(negotiated.responseCodec()).isSameAs(mockAvroCodec);
    }

    @Test
    @DisplayName(
            "GIVEN Accept with highest quality Avro first, then lower quality Protobuf WHEN negotiate called SHOULD select Avro")
    void given_acceptWithQualityWeighting_when_negotiate_should_selectHighestQuality() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "avro/binary;q=0.9, application/protobuf;q=0.5");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: avro/binary has higher quality (0.9 vs 0.5)
        assertThat(negotiated.responseCodec()).isSameAs(mockAvroCodec);
    }

    @Test
    @DisplayName(
            "GIVEN Accept with Protobuf q=0.9 before Avro q=0.8 (order-independent) WHEN negotiate called SHOULD select Protobuf")
    void given_acceptOrderIndependent_when_negotiate_should_selectByQuality() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "avro/binary;q=0.8, application/protobuf;q=0.9");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: protobuf has higher quality (0.9 vs 0.8) despite appearing second
        assertThat(negotiated.responseCodec()).isSameAs(mockProtobufCodec);
    }

    @Test
    @DisplayName("GIVEN Accept: application/* WHEN negotiate called SHOULD match as wildcard and select JSON")
    void given_applicationWildcardAccept_when_negotiate_should_selectJson() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/*");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: application/* is a wildcard, treated as JSON
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN Accept: */* WHEN negotiate called SHOULD match as full wildcard and select JSON")
    void given_fullWildcardAccept_when_negotiate_should_selectJson() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "*/*");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: */* is a wildcard, treated as JSON
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN Accept with quality value 0 (reject) for all codecs WHEN negotiate called SHOULD throw NotAcceptable")
    void given_acceptWithQualityZero_when_negotiate_should_throwNotAcceptable() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "avro/binary;q=0.0, application/protobuf;q=0.0");

        // Act & Assert
        assertThatThrownBy(() -> filterWithAvroCodec.negotiate(headers))
                .isInstanceOf(ShiguchiFilter.NotAcceptable.class)
                .hasMessageContaining("None of the requested media types is supported");
    }

    @Test
    @DisplayName(
            "GIVEN Accept with application/unknown WHEN negotiate called SHOULD throw NotAcceptable if not supported")
    void given_acceptWithUnsupportedType_when_negotiate_should_throwNotAcceptable() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/xml");

        // Act & Assert
        assertThatThrownBy(() -> filterWithAvroCodec.negotiate(headers))
                .isInstanceOf(ShiguchiFilter.NotAcceptable.class)
                .hasMessageContaining("None of the requested media types is supported");
    }

    @Test
    @DisplayName(
            "GIVEN Accept: avro/binary;q=0 (reject), application/json;q=0.9 WHEN negotiate called SHOULD select JSON")
    void given_acceptWithMixedQuality_when_negotiate_should_selectSupportedType() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "avro/binary;q=0.0, application/json;q=0.9");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: avro has quality 0 (rejected), JSON has quality 0.9 (accepted)
        assertThat(negotiated.responseCodec()).isNull(); // JSON selected
    }

    @Test
    @DisplayName("GIVEN no Accept header WHEN negotiate called SHOULD default to JSON response codec")
    void given_noAcceptHeader_when_negotiate_should_defaultToJson() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: no Accept header means accept anything, defaulting to JSON
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN both Content-Type: avro/binary and Accept: application/protobuf WHEN negotiate called SHOULD select both respective codecs")
    void given_mixedContentTypeAndAccept_when_negotiate_should_selectEachCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "avro/binary");
        headers.set(HttpHeaders.ACCEPT, "application/protobuf");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: request uses Avro, response uses Protobuf
        assertThat(negotiated.requestCodec()).isSameAs(mockAvroCodec);
        assertThat(negotiated.responseCodec()).isSameAs(mockProtobufCodec);
    }

    @Test
    @DisplayName(
            "GIVEN Accept: avro/binary, application/json with equal quality WHEN negotiate called SHOULD prefer the first (Avro)")
    void given_acceptWithEqualQuality_when_negotiate_should_selectFirst() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        // Both have implicit default quality of 1.0
        headers.set(HttpHeaders.ACCEPT, "avro/binary, application/json");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: when quality is equal, the first one listed is selected
        // Note: MediaType.sortByQualityValue preserves original order for equal qualities
        // So avro/binary (first with quality 1.0) should be selected
        assertThat(negotiated.responseCodec()).isSameAs(mockAvroCodec);
    }

    @Test
    @DisplayName("GIVEN null Content-Type header WHEN negotiate called SHOULD treat as no Content-Type (JSON)")
    void given_nullContentType_when_negotiate_should_defaultToJson() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        // No Content-Type set, so getContentType() returns null

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert
        assertThat(negotiated.requestCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN Content-Type with wildcard WHEN negotiate called SHOULD treat as JSON (wildcard means accept any)")
    void given_wildcardContentType_when_negotiate_should_treatAsJson() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "*/*");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: wildcard content type is treated as JSON
        assertThat(negotiated.requestCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN Accept with multiple supported codecs (avro, protobuf) and JSON WHEN negotiate called SHOULD select first matching by quality")
    void given_acceptWithMultipleSupportedTypes_when_negotiate_should_selectByQuality() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, avro/binary, application/protobuf");

        // Act
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: JSON and wildcard stop the search first, so response is null (JSON)
        assertThat(negotiated.responseCodec()).isNull();
    }

    // -------- SSE and NDJSON streaming format tests --------

    @Test
    @DisplayName(
            "GIVEN Avro codec installed and Accept: application/x-ndjson WHEN negotiate called SHOULD not throw and return null response codec")
    void given_avroCodecAndAcceptNdjson_when_negotiate_should_returnNullResponseCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/x-ndjson");

        // Act: should not throw NotAcceptable or UnsupportedMediaType
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: application/x-ndjson is a streaming format, returns null codec (JSON framing)
        assertThat(negotiated.requestCodec()).isNull();
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN Avro codec installed and Accept: text/event-stream WHEN negotiate called SHOULD not throw and return null response codec")
    void given_avroCodecAndAcceptSse_when_negotiate_should_returnNullResponseCodec() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "text/event-stream");

        // Act: should not throw NotAcceptable or UnsupportedMediaType
        var negotiated = filterWithAvroCodec.negotiate(headers);

        // Assert: text/event-stream is a streaming format, returns null codec (JSON framing)
        assertThat(negotiated.requestCodec()).isNull();
        assertThat(negotiated.responseCodec()).isNull();
    }

    @Test
    @DisplayName("GIVEN Accept: text/event-stream WHEN streamsAsSse called SHOULD return true")
    void given_acceptTextEventStream_when_streamsAsSse_should_returnTrue() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "text/event-stream");

        // Act
        boolean result = filterWithNoCodecs.streamsAsSse(headers);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("GIVEN Accept: application/x-ndjson WHEN streamsAsSse called SHOULD return false")
    void given_acceptApplicationNdjson_when_streamsAsSse_should_returnFalse() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/x-ndjson");

        // Act
        boolean result = filterWithNoCodecs.streamsAsSse(headers);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("GIVEN Accept: application/json WHEN streamsAsSse called SHOULD return false")
    void given_acceptApplicationJson_when_streamsAsSse_should_returnFalse() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json");

        // Act
        boolean result = filterWithNoCodecs.streamsAsSse(headers);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("GIVEN no Accept header and default format NDJSON WHEN streamsAsSse called SHOULD return false")
    void given_noAcceptHeaderWithDefaultNdjson_when_streamsAsSse_should_returnFalse() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        // No Accept header set

        // Act
        boolean result = filterWithNoCodecs.streamsAsSse(headers);

        // Assert: defaults to NDJSON (false)
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("GIVEN Accept: */* (wildcard) WHEN streamsAsSse called SHOULD return false and fall back to default")
    void given_acceptWildcard_when_streamsAsSse_should_returnFalse() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "*/*");

        // Act
        boolean result = filterWithNoCodecs.streamsAsSse(headers);

        // Assert: wildcard does not explicitly request SSE, falls back to default NDJSON
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN Accept with quality ranking (NDJSON 0.5, SSE 1.0) WHEN streamsAsSse called SHOULD return true (highest quality wins)")
    void given_acceptWithQualityRankingSseHigher_when_streamsAsSse_should_returnTrue() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/x-ndjson;q=0.5, text/event-stream");

        // Act: SSE has implicit quality 1.0, NDJSON has 0.5
        boolean result = filterWithNoCodecs.streamsAsSse(headers);

        // Assert: SSE with higher quality is selected
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN Accept: text/event-stream, application/json (equal quality) WHEN streamsAsSse called SHOULD return true (SSE appears first)")
    void given_acceptSseAndJsonEqualQuality_when_streamsAsSse_should_returnTrue() {
        // Arrange
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "text/event-stream, application/json");

        // Act: both have implicit quality 1.0, stable sort preserves order
        boolean result = filterWithNoCodecs.streamsAsSse(headers);

        // Assert: SSE is encountered first among equal qualities
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("GIVEN default format SSE and no Accept header WHEN streamsAsSse called SHOULD return true")
    void given_sseDefaultFormatAndNoAccept_when_streamsAsSse_should_returnTrue() {
        // Arrange: create a filter with SSE as default format
        var properties = new GatewayProperties();
        properties
                .getRest()
                .getStreaming()
                .setDefaultFormat(dev.appiaworks.shiguchi.config.RestConfig.StreamFormat.SSE);

        var filterWithSseDefault = new ShiguchiFilter(
                new GrpcRouteRegistry(),
                new GrpcPathMatcher(),
                org.mockito.Mockito.mock(ReactiveGrpcDispatcher.class),
                org.mockito.Mockito.mock(dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator.class),
                org.mockito.Mockito.mock(dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer.class),
                org.mockito.Mockito.mock(GrpcStatusMapper.class),
                org.mockito.Mockito.mock(GrpcResponsePaginationExtractor.class),
                org.mockito.Mockito.mock(dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer.class),
                org.mockito.Mockito.mock(MergeDispatcher.class),
                org.mockito.Mockito.mock(GrpcRequestBuilder.class),
                org.mockito.Mockito.mock(SerdeSelector.class),
                properties,
                List.of());

        HttpHeaders headers = new HttpHeaders();
        // No Accept header

        // Act
        boolean result = filterWithSseDefault.streamsAsSse(headers);

        // Assert: default format SSE is used
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN default format SSE but explicit Accept: application/x-ndjson WHEN streamsAsSse called SHOULD return false (explicit wins)")
    void given_sseDefaultFormatButExplicitNdjson_when_streamsAsSse_should_returnFalse() {
        // Arrange: create a filter with SSE as default format
        var properties = new GatewayProperties();
        properties
                .getRest()
                .getStreaming()
                .setDefaultFormat(dev.appiaworks.shiguchi.config.RestConfig.StreamFormat.SSE);

        var filterWithSseDefault = new ShiguchiFilter(
                new GrpcRouteRegistry(),
                new GrpcPathMatcher(),
                org.mockito.Mockito.mock(ReactiveGrpcDispatcher.class),
                org.mockito.Mockito.mock(dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator.class),
                org.mockito.Mockito.mock(dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer.class),
                org.mockito.Mockito.mock(GrpcStatusMapper.class),
                org.mockito.Mockito.mock(GrpcResponsePaginationExtractor.class),
                org.mockito.Mockito.mock(dev.appiaworks.shiguchi.gateway.GatewayErrorRenderer.class),
                org.mockito.Mockito.mock(MergeDispatcher.class),
                org.mockito.Mockito.mock(GrpcRequestBuilder.class),
                org.mockito.Mockito.mock(SerdeSelector.class),
                properties,
                List.of());

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/x-ndjson");

        // Act
        boolean result = filterWithSseDefault.streamsAsSse(headers);

        // Assert: explicit Accept header overrides default
        assertThat(result).isFalse();
    }
}
