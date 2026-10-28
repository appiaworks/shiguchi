package dev.appiaworks.shiguchi.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Encodes a {@link MergeCursor} as one URL-safe string and reads it back.
 *
 * <p>The wire form is gzipped JSON in URL-safe base64 without padding, so it survives a query
 * parameter untouched. Sources are keyed by <em>ordinal</em> into the route's source list rather
 * than by {@code service/method}: with several sources those names would dominate the payload, and
 * the cursor has to stay inside a URL.
 *
 * <p>The result is opaque to callers by intent — same contract as a backend {@code page_token}.
 * Callers must round-trip it verbatim; nothing about its structure is API surface.
 *
 * <p>Every decode failure is a client error, never a server one. A cursor that is truncated,
 * corrupted, hand-edited, or minted for a route whose source list has since changed is rejected
 * outright rather than repaired — silently resetting to page 1 would hand back rows the caller has
 * already seen and look like data duplication.
 */
public final class MergeCursorCodec {

    /**
     * Ceiling on the encoded cursor, in characters. Proxies and servers cap URL length (commonly
     * around 8 KB for the whole request line), and a cursor grows linearly with source count, so
     * this fails loudly at mint time rather than letting a downstream hop truncate it into
     * corruption.
     */
    public static final int MAX_ENCODED_LENGTH = 4096;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private MergeCursorCodec() {}

    /**
     * @throws IllegalStateException if the encoded form exceeds {@link #MAX_ENCODED_LENGTH}
     */
    public static String encode(MergeCursor cursor) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("k", cursor.lastKey());
            ArrayNode sources = root.putArray("s");
            for (MergeCursor.SourcePosition position : cursor.sources()) {
                ObjectNode entry = sources.addObject();
                entry.put("t", position.token());
                entry.put("n", position.emittedAtLastKey());
            }

            byte[] json = MAPPER.writeValueAsBytes(root);
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(json);
            }
            String encoded = ENCODER.encodeToString(compressed.toByteArray());

            if (encoded.length() > MAX_ENCODED_LENGTH) {
                throw new IllegalStateException("merge cursor is %d chars, over the %d limit — too many sources, or "
                                .formatted(encoded.length(), MAX_ENCODED_LENGTH)
                        + "backend page tokens too large, to page this merge safely");
            }
            return encoded;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode merge cursor", e);
        }
    }

    /**
     * @param encoded cursor exactly as handed to the caller
     * @param expectedSources source count of the route being paged; a mismatch means the cursor
     *     belongs to a different route, or to this one before its sources changed — either way its
     *     ordinals no longer address what they did
     * @throws InvalidCursorException if the cursor cannot be trusted to mean what it says
     */
    public static MergeCursor decode(String encoded, int expectedSources) {
        if (encoded == null || encoded.isBlank()) {
            throw new InvalidCursorException("cursor is empty");
        }
        if (encoded.length() > MAX_ENCODED_LENGTH) {
            throw new InvalidCursorException("cursor exceeds the maximum supported length");
        }

        byte[] json;
        try {
            byte[] compressed = DECODER.decode(encoded);
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                json = gzip.readAllBytes();
            }
        } catch (Exception e) {
            throw new InvalidCursorException("cursor is not a well-formed gateway cursor", e);
        }

        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode keyNode = root.get("k");
            JsonNode sourcesNode = root.get("s");
            if (keyNode == null || !keyNode.isTextual() || sourcesNode == null || !sourcesNode.isArray()) {
                throw new InvalidCursorException("cursor is missing its sort key or source list");
            }
            if (sourcesNode.size() != expectedSources) {
                throw new InvalidCursorException("cursor covers %d sources but this route merges %d — it was issued "
                                .formatted(sourcesNode.size(), expectedSources)
                        + "for a different route, or before this route's sources changed");
            }

            List<MergeCursor.SourcePosition> positions = new ArrayList<>(sourcesNode.size());
            for (JsonNode entry : sourcesNode) {
                JsonNode token = entry.get("t");
                JsonNode emitted = entry.get("n");
                if (token == null || !token.isTextual() || emitted == null || !emitted.isInt()) {
                    throw new InvalidCursorException("cursor has a malformed source entry");
                }
                if (emitted.asInt() < 0) {
                    throw new InvalidCursorException("cursor has a negative tie count");
                }
                positions.add(new MergeCursor.SourcePosition(token.asText(), emitted.asInt()));
            }
            return new MergeCursor(keyNode.asText(), positions);
        } catch (InvalidCursorException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCursorException("cursor could not be read", e);
        }
    }

    /** Signals a cursor the gateway will not act on. Surfaces as a 400, never a 500. */
    public static class InvalidCursorException extends RuntimeException {
        public InvalidCursorException(String message) {
            super(message);
        }

        public InvalidCursorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
