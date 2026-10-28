package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.regex.Pattern;

/**
 * A push topic: {@code namespace:name}, e.g. {@code orders:123}, {@code user:alice},
 * {@code tenant:acme/alerts}. The namespace carries the policy (delivery, history, authorization),
 * the name is free within it. Split on the first colon, so a name may itself contain colons.
 *
 * @param namespace the configured namespace, {@code [A-Za-z0-9_.-]}, at most 64 characters
 * @param name the topic within it, {@code [A-Za-z0-9_./:@-]}, at most 192 characters
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public record Topic(String namespace, String name) {

    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_./:@-]{1,192}");

    public Topic {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("invalid topic namespace '" + namespace + "'");
        }
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid topic name '" + name + "'");
        }
    }

    /** Whether {@code name} may be a topic name. */
    static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** Parses {@code namespace:name}; throws {@link IllegalArgumentException} on anything else. */
    public static Topic parse(String text) {
        if (text == null) throw new IllegalArgumentException("topic is required");
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            throw new IllegalArgumentException("topic '" + text + "' must be namespace:name");
        }
        return new Topic(text.substring(0, colon), text.substring(colon + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + name;
    }
}
