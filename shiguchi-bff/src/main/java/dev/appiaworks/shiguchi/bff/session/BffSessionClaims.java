package dev.appiaworks.shiguchi.bff.session;

import com.nimbusds.jose.util.JSONObjectUtils;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ID-token claims a session keeps for {@code /bff/session}: the ones an application lists under
 * {@code session-claims}, other than {@code name}, {@code email} and {@code picture} (which have
 * fields of their own). Values are strings, numbers, booleans, lists of those and maps of those, at
 * most two levels deep; a claim of any other shape is dropped. The serialized claims of one session
 * stay within {@link #MAX_BYTES}; claims past that are dropped with a warning that names them, never
 * their values. Stores keep them as one JSON text, which reads back as "no claims" when damaged.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class BffSessionClaims {

    private static final Logger log = LoggerFactory.getLogger(BffSessionClaims.class);

    /** The most bytes (UTF-8 JSON) the claims of one session may take. */
    public static final int MAX_BYTES = 4096;

    /** Claims with fields of their own on the session. */
    public static final Set<String> FIXED = Set.of("name", "email", "picture");

    private static final int MAX_DEPTH = 2;

    private BffSessionClaims() {}

    /**
     * Picks the claims to keep.
     *
     * @param source the verified ID token's claims
     * @param names the claim names the application lists
     * @param application the application id, for the warning
     * @return the claims to store, in the order of {@code names}; empty when none apply
     */
    public static Map<String, Object> select(Map<String, Object> source, Collection<String> names, String application) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null || names == null) return out;
        List<String> dropped = new ArrayList<>();
        for (String name : names) {
            if (name == null || FIXED.contains(name) || !source.containsKey(name)) continue;
            Object value = sanitize(source.get(name), 1);
            if (value == null) {
                dropped.add(name);
                continue;
            }
            Map<String, Object> candidate = new LinkedHashMap<>(out);
            candidate.put(name, value);
            if (size(candidate) > MAX_BYTES) {
                dropped.add(name);
                continue;
            }
            out = candidate;
        }
        if (!dropped.isEmpty()) {
            log.warn(
                    "BFF: session claims {} of application '{}' were not kept: unsupported shape (more than two"
                            + " levels deep, or not JSON-like) or over the {} byte limit per session",
                    dropped,
                    application,
                    MAX_BYTES);
        }
        return out;
    }

    /** The JSON text of {@code claims}, or {@code null} when there are none. */
    public static String toJson(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) return null;
        return JSONObjectUtils.toJSONString(claims);
    }

    /** The claims of a stored JSON text; empty for {@code null}, blank or damaged text. Never throws. */
    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = JSONObjectUtils.parse(json);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception | LinkageError e) {
            return Map.of();
        }
    }

    private static int size(Map<String, Object> claims) {
        return JSONObjectUtils.toJSONString(claims).getBytes(StandardCharsets.UTF_8).length;
    }

    /** The value in a JSON-friendly form, or {@code null} when its shape is not supported. */
    private static Object sanitize(Object value, int depth) {
        if (value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : value;
        }
        if (depth > MAX_DEPTH) return null;
        if (value instanceof Collection<?> items) {
            List<Object> out = new ArrayList<>();
            for (Object item : items) {
                Object clean = sanitize(item, depth + 1);
                if (clean == null) return null;
                out.add(clean);
            }
            return out;
        }
        if (value instanceof Map<?, ?> entries) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : entries.entrySet()) {
                if (!(e.getKey() instanceof String key)) return null;
                Object clean = sanitize(e.getValue(), depth + 1);
                if (clean == null) return null;
                out.put(key, clean);
            }
            return out;
        }
        return null;
    }
}
