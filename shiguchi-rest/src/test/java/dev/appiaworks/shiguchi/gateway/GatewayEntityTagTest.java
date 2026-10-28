package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Conditional GET: entity tags on responses and If-None-Match matching.
 * Tests RenderedResponse static methods and ShiguchiFilter static validators.
 */
@DisplayName("Conditional GET")
class GatewayEntityTagTest {

    private static final byte[] BODY = "{\"id\":\"1\",\"name\":\"Anvil\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("GIVEN a response body WHEN entityTag called SHOULD return a strong quoted 32-hex tag")
    void given_responseBody_when_entityTag_should_returnStrongTag() {
        var tag = RenderedResponse.entityTag(BODY);

        assertThat(tag).matches("\"[0-9a-f]{32}\"");
    }

    @Test
    @DisplayName("GIVEN identical bodies WHEN entityTag called SHOULD produce the same tag")
    void given_identicalBodies_when_entityTag_should_produceIdenticalTags() {
        var tag1 = RenderedResponse.entityTag(BODY);
        var tag2 = RenderedResponse.entityTag(BODY.clone());

        assertThat(tag1).isEqualTo(tag2);
    }

    @Test
    @DisplayName("GIVEN different bodies WHEN entityTag called SHOULD produce different tags")
    void given_differentBodies_when_entityTag_should_produceDifferentTags() {
        var tag1 = RenderedResponse.entityTag(BODY);
        var tag2 = RenderedResponse.entityTag("{\"id\":\"2\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(tag1).isNotEqualTo(tag2);
    }

    @Test
    @DisplayName("GIVEN an empty body WHEN entityTag called SHOULD still produce a valid tag")
    void given_emptyBody_when_entityTag_should_produceValidTag() {
        var tag = RenderedResponse.entityTag(new byte[0]);

        assertThat(tag).matches("\"[0-9a-f]{32}\"");
    }

    @Test
    @DisplayName("GIVEN a response body WHEN gzip called SHOULD compress and decompress properly")
    void given_responseBody_when_gzip_should_roundTrip() throws Exception {
        var gzipped = RenderedResponse.gzip(BODY);

        var out = new ByteArrayInputStream(gzipped);
        var gz = new GZIPInputStream(out);
        var decompressed = gz.readAllBytes();

        assertThat(decompressed).isEqualTo(BODY);
    }

    @Test
    @DisplayName("GIVEN exact tag match in If-None-Match WHEN notModified called SHOULD return true")
    void given_exactTagMatch_when_notModified_should_returnTrue() {
        var tag = RenderedResponse.entityTag(BODY);
        var headers = new HttpHeaders();
        headers.setIfNoneMatch(tag);

        var result = ShiguchiFilter.notModified(headers, tag, tag);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("GIVEN W/-weakened tag in If-None-Match WHEN notModified called SHOULD return true")
    void given_weakenedTagMatch_when_notModified_should_returnTrue() {
        var tag = RenderedResponse.entityTag(BODY);
        var weak = "W/" + tag;
        var headers = new HttpHeaders();
        headers.setIfNoneMatch(weak);

        var result = ShiguchiFilter.notModified(headers, tag, tag);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("GIVEN wildcard '*' in If-None-Match WHEN notModified called SHOULD return true")
    void given_wildcard_when_notModified_should_returnTrue() {
        var tag = RenderedResponse.entityTag(BODY);
        var headers = new HttpHeaders();
        headers.setIfNoneMatch("*");

        var result = ShiguchiFilter.notModified(headers, tag, tag);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("GIVEN comma-separated tag list with matching tag WHEN notModified called SHOULD return true")
    void given_multipleTagsWithMatch_when_notModified_should_returnTrue() {
        var tag = RenderedResponse.entityTag(BODY);
        var others = "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\", " + tag;
        var headers = new HttpHeaders();
        headers.setIfNoneMatch(others);

        var result = ShiguchiFilter.notModified(headers, tag, tag);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("GIVEN gzip representation tag as 'sent' WHEN notModified called SHOULD match both base and sent")
    void given_gzipRepresentationTag_when_notModified_should_matchSent() {
        var tag = RenderedResponse.entityTag(BODY);
        var gzipTag = RenderedResponse.entityTag(RenderedResponse.gzip(BODY));
        var headers = new HttpHeaders();
        headers.setIfNoneMatch(tag);

        var result = ShiguchiFilter.notModified(headers, tag, gzipTag);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("GIVEN stale tag not matching WHEN notModified called SHOULD return false")
    void given_staleTag_when_notModified_should_returnFalse() {
        var currentTag = RenderedResponse.entityTag(BODY);
        var staleTag = "\"0000000000000000000000000000000\"";
        var headers = new HttpHeaders();
        headers.setIfNoneMatch(staleTag);

        var result = ShiguchiFilter.notModified(headers, currentTag, currentTag);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("GIVEN no If-None-Match header WHEN notModified called SHOULD return false")
    void given_noIfNoneMatch_when_notModified_should_returnFalse() {
        var tag = RenderedResponse.entityTag(BODY);
        var headers = new HttpHeaders();

        var result = ShiguchiFilter.notModified(headers, tag, tag);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("GIVEN gzip in Accept-Encoding WHEN acceptsGzip called SHOULD return true")
    void given_gzipInAcceptEncoding_when_acceptsGzip_should_returnTrue() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");

        assertThat(ShiguchiFilter.acceptsGzip(headers)).isTrue();
    }

    @Test
    @DisplayName("GIVEN x-gzip in Accept-Encoding WHEN acceptsGzip called SHOULD return true")
    void given_xGzipInAcceptEncoding_when_acceptsGzip_should_returnTrue() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "x-gzip");

        assertThat(ShiguchiFilter.acceptsGzip(headers)).isTrue();
    }

    @Test
    @DisplayName("GIVEN wildcard '*' in Accept-Encoding WHEN acceptsGzip called SHOULD return true")
    void given_wildcardInAcceptEncoding_when_acceptsGzip_should_returnTrue() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "*");

        assertThat(ShiguchiFilter.acceptsGzip(headers)).isTrue();
    }

    @Test
    @DisplayName("GIVEN 'gzip;q=0' in Accept-Encoding WHEN acceptsGzip called SHOULD return false")
    void given_gzipWithQZero_when_acceptsGzip_should_returnFalse() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip;q=0");

        assertThat(ShiguchiFilter.acceptsGzip(headers)).isFalse();
    }

    @Test
    @DisplayName("GIVEN 'br' in Accept-Encoding without gzip WHEN acceptsGzip called SHOULD return false")
    void given_brOnly_when_acceptsGzip_should_returnFalse() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "br");

        assertThat(ShiguchiFilter.acceptsGzip(headers)).isFalse();
    }

    @Test
    @DisplayName("GIVEN no Accept-Encoding header WHEN acceptsGzip called SHOULD return false")
    void given_noAcceptEncoding_when_acceptsGzip_should_returnFalse() {
        var headers = new HttpHeaders();

        assertThat(ShiguchiFilter.acceptsGzip(headers)).isFalse();
    }
}
