package dev.appiaworks.shiguchi.routing;

import com.google.api.HttpRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads a {@code google.api.http} rule into the (method, path template, body field) bindings the
 * gateway registers as REST routes. The first binding is the rule's own pattern; every entry of
 * {@code additional_bindings} follows in declaration order (one level only, as the option's own
 * documentation requires).
 *
 * <p>{@code body} is the top-level request field the HTTP body maps to, {@code "*"} for the
 * whole request message, and {@code null} when the rule does not say. {@code responseBody} is the
 * mirror of it: the single response field to send as the HTTP body, or {@code null} for the whole
 * message.
 */
public final class HttpRuleBindings {

    /**
     * One REST binding of an RPC.
     *
     * @param body a top-level request field name, {@code "*"} for the whole message, or null
     * @param responseBody a top-level response field to send as the body, or null for the whole
     *     message. {@code "*"} is not meaningful here and is read as null, matching the option's
     *     own definition.
     */
    public record Binding(String method, String path, String body, String responseBody) {}

    private HttpRuleBindings() {}

    /** All bindings of the rule in declaration order; empty when the rule carries no pattern. */
    public static List<Binding> of(HttpRule rule) {
        if (rule == null) return List.of();
        List<Binding> bindings = new ArrayList<>();
        single(rule).ifPresent(bindings::add);
        for (HttpRule additional : rule.getAdditionalBindingsList()) {
            single(additional).ifPresent(bindings::add);
        }
        return List.copyOf(bindings);
    }

    /**
     * The binding {@code rest.auto-mapping} gives a method without a rule: {@code POST} on the
     * method's gRPC path, {@code /package.Service/Method}, with the whole body as the request
     * message. Using the gRPC path keeps it clear of any annotated route.
     */
    public static Binding autoMapped(String fullServiceName, String methodName) {
        return new Binding("POST", "/" + fullServiceName + "/" + methodName, "*", null);
    }

    private static Optional<Binding> single(HttpRule rule) {
        String method;
        String path;
        switch (rule.getPatternCase()) {
            case GET -> {
                method = "GET";
                path = rule.getGet();
            }
            case POST -> {
                method = "POST";
                path = rule.getPost();
            }
            case PUT -> {
                method = "PUT";
                path = rule.getPut();
            }
            case DELETE -> {
                method = "DELETE";
                path = rule.getDelete();
            }
            case PATCH -> {
                method = "PATCH";
                path = rule.getPatch();
            }
            case CUSTOM -> {
                // A custom pattern names any verb (HEAD, OPTIONS, ...). The registry keys routes
                // by method string, so it needs no more support than the five above.
                method = rule.getCustom().getKind().trim().toUpperCase(Locale.ROOT);
                path = rule.getCustom().getPath();
            }
            default -> {
                return Optional.empty();
            }
        }
        if (method.isEmpty() || path == null || path.isBlank()) return Optional.empty();
        String body = rule.getBody().isBlank() ? null : rule.getBody().trim();
        // response_body names one field; "*" means the whole message, which is the default, so
        // both it and an absent value resolve to null.
        String responseBody =
                rule.getResponseBody().isBlank() ? null : rule.getResponseBody().trim();
        if ("*".equals(responseBody)) responseBody = null;
        return Optional.of(new Binding(method, path, body, responseBody));
    }
}
