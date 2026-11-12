package dev.appiaworks.shiguchi.backend;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encodes and decodes the opaque {@code page_token}.
 *
 * <p>The gateway never looks inside a token, so the encoding is yours. This one carries a small
 * string map (an offset, or the last row's sort key), and can be <em>signed</em> with HMAC-SHA256 so
 * a client cannot forge a cursor to read from a position it was not handed:
 *
 * <pre>{@code
 * PageCursor cursor = PageCursor.signed(secretBytes);
 * }</pre>
 *
 * <p>Signing proves the token came from you, not that it is fresh; a client can replay an old one
 * and get that page back. Use {@link #unsigned()} when a forged position is harmless (an offset
 * into data the caller may read anyway).
 *
 * <p>A malformed, oversized or badly signed token is answered with {@code INVALID_ARGUMENT}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public final class PageCursor {

    private static final int MAX_TOKEN_LENGTH = 2048;
    private static final String HMAC = "HmacSHA256";
    private static final PageCursor UNSIGNED = new PageCursor(null);

    private final byte[] secret;

    private PageCursor(byte[] secret) {
        this.secret = secret;
    }

    public static PageCursor unsigned() {
        return UNSIGNED;
    }

    /** @param secret the signing key; keep it stable across instances, or tokens stop validating */
    public static PageCursor signed(byte[] secret) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("Use a signing secret of at least 16 bytes");
        }
        return new PageCursor(secret.clone());
    }

    public String encode(Map<String, String> values) {
        Struct.Builder struct = Struct.newBuilder();
        values.forEach((k, v) ->
                struct.putFields(k, Value.newBuilder().setStringValue(v).build()));
        byte[] payload = struct.build().toByteArray();
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        return secret == null
                ? body
                : body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }

    /** The values in {@code token}; empty for a null or blank token (the first page). */
    public Map<String, String> decode(String token) {
        if (token == null || token.isBlank()) {
            return Map.of();
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw malformed();
        }
        try {
            String body = token;
            byte[] signature = null;
            int dot = token.indexOf('.');
            if (secret != null) {
                if (dot < 0) {
                    throw malformed();
                }
                body = token.substring(0, dot);
                signature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            } else if (dot >= 0) {
                throw malformed();
            }
            byte[] payload = Base64.getUrlDecoder().decode(body);
            if (signature != null && !MessageDigest.isEqual(signature, mac(payload))) {
                throw malformed();
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, Value> entry :
                    Struct.parseFrom(payload).getFieldsMap().entrySet()) {
                if (entry.getValue().getKindCase() != Value.KindCase.STRING_VALUE) {
                    throw malformed();
                }
                values.put(entry.getKey(), entry.getValue().getStringValue());
            }
            return values;
        } catch (IllegalArgumentException | InvalidProtocolBufferException e) {
            throw malformed();
        }
    }

    /** The offset a token carries, or 0 for the first page. */
    public int decodeOffset(String token) {
        String offset = decode(token).get("o");
        if (offset == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(offset), 0);
        } catch (NumberFormatException e) {
            throw malformed();
        }
    }

    public String encodeOffset(int offset) {
        return encode(Map.of("o", Integer.toString(offset)));
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static RuntimeException malformed() {
        return Names.invalid("Malformed page_token");
    }
}
