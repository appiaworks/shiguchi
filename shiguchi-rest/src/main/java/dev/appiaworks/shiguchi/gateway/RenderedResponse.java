package dev.appiaworks.shiguchi.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * A successful unary REST answer before it is written: body, content type and the headers that
 * belong to the answer itself (pagination, a codec's headers, forwarded backend headers).
 *
 * <p>Kept apart from the exchange so the same answer can be written to several callers — the
 * response cache and request coalescing hand one to everyone who asked for it — and so what is
 * derived from the body (its hash tag, its gzip form) is computed once however often it is sent.
 */
final class RenderedResponse {

    private final MediaType contentType;
    private final HttpHeaders headers;
    private final byte[] body;

    /** A resource {@code etag} field (AIP-154), already a valid entity tag; null when there is none. */
    private final String resourceEtag;

    private volatile String hashTag;
    private volatile byte[] gzipped;

    RenderedResponse(MediaType contentType, HttpHeaders headers, byte[] body, String resourceEtag) {
        this.contentType = contentType;
        this.headers = headers == null ? new HttpHeaders() : headers;
        this.body = body;
        this.resourceEtag = resourceEtag;
    }

    MediaType contentType() {
        return contentType;
    }

    /** The answer's own headers; never includes caching or encoding headers, which are per send. */
    HttpHeaders headers() {
        return headers;
    }

    byte[] body() {
        return body;
    }

    String resourceEtag() {
        return resourceEtag;
    }

    /**
     * A strong tag over the body: the first 128 bits of its SHA-256, quoted. Exact rather than
     * heuristic — two answers share a tag precisely when their bytes are identical, and a backend
     * returning the same data returns the same bytes since serialization is deterministic.
     */
    String hashTag() {
        String tag = hashTag;
        if (tag == null) {
            tag = hashTag = entityTag(body);
        }
        return tag;
    }

    /** The body gzipped, computed on first use and kept for every later send. */
    byte[] gzipped() {
        byte[] out = gzipped;
        if (out == null) {
            out = gzipped = gzip(body);
        }
        return out;
    }

    static String entityTag(byte[] body) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(body);
            // A 128-bit prefix is far past any realistic collision for cache validation, and the
            // header goes out on every response.
            StringBuilder hex = new StringBuilder(34).append('"');
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(digest[i] & 0xf, 16));
            }
            return hex.append('"').toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    static byte[] gzip(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, body.length / 3));
        try (GZIPOutputStream gz = new GZIPOutputStream(out, 8192)) {
            gz.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
