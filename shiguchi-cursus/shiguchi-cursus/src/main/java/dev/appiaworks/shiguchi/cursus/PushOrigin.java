package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The origin of an event: a declared id naming who it came from, carried in the reserved attribute
 * {@code push.origin}. A subscriber that declares the same origin does not receive the event
 * (echo suppression). The id is a value the client knows and a backend can pass on when it
 * publishes for that client; it is not a secret and not an identity the gateway checks.
 *
 * <p>The {@code push.} attribute prefix is reserved for the gateway: client-facing entry points
 * drop such attributes, operator entry points refuse them.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class PushOrigin {

    /** The attribute that carries an event's origin. */
    public static final String ATTRIBUTE = "push.origin";

    /** The attribute-key prefix reserved for the gateway. */
    public static final String RESERVED_PREFIX = "push.";

    private static final Pattern GRAMMAR = Pattern.compile("[A-Za-z0-9_.:@-]{1,128}");

    private PushOrigin() {}

    /** {@code raw} validated against the grammar; null when blank or absent. */
    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String origin = raw.trim();
        if (!GRAMMAR.matcher(origin).matches()) {
            throw PushException.badRequest(
                    "origin must be 1-128 characters of letters, digits, '.', '_', ':', '@' or '-', got '" + raw + "'");
        }
        return origin;
    }

    /** True when {@code event} carries {@code origin}; false for a null origin. */
    static boolean matches(PushEvent event, String origin) {
        return origin != null && origin.equals(event.attributes().get(ATTRIBUTE));
    }

    /**
     * An operator publish's attributes: {@code push.}-prefixed keys are refused, {@code origin} (when
     * given) and {@code from} (when non-null) are stamped over whatever the caller sent.
     */
    static Map<String, String> forOperator(Map<String, String> attributes, String origin, String from) {
        Map<String, String> out = new LinkedHashMap<>();
        if (attributes != null) {
            for (Map.Entry<String, String> a : attributes.entrySet()) {
                if (a.getKey().startsWith(RESERVED_PREFIX)) {
                    throw PushException.badRequest("attribute '" + a.getKey() + "' is reserved: the '" + RESERVED_PREFIX
                            + "' prefix belongs to the gateway");
                }
                out.put(a.getKey(), a.getValue());
            }
        }
        String checked = parse(origin);
        if (checked != null) out.put(ATTRIBUTE, checked);
        if (from != null) out.put("from", from);
        return out;
    }

    /**
     * A client publish's attributes: {@code push.}-prefixed keys and {@code from} are dropped
     * without notice, then the connection's declared {@code origin} (when it declared one) and the
     * publisher's name ({@code from}, when authenticated) are stamped: the client never supplies
     * either.
     */
    static Map<String, String> forClient(Map<String, String> attributes, String origin, String from) {
        Map<String, String> out = new LinkedHashMap<>();
        if (attributes != null) {
            for (Map.Entry<String, String> a : attributes.entrySet()) {
                if (!a.getKey().startsWith(RESERVED_PREFIX) && !"from".equals(a.getKey())) {
                    out.put(a.getKey(), a.getValue());
                }
            }
        }
        if (origin != null) out.put(ATTRIBUTE, origin);
        if (from != null) out.put("from", from);
        return out;
    }
}
