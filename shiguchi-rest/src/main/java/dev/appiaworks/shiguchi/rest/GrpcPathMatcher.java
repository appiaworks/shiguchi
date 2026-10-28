package dev.appiaworks.shiguchi.rest;

import dev.appiaworks.shiguchi.routing.HttpPathTemplate;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.Collections;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Extracts path template variables from an actual request path.
 *
 * <p>Example: template {@code /v1/{name=shelves/*}/books/{id}}, path
 * {@code /v1/shelves/s1/books/a%20b} → {@code {"name": "shelves/s1", "id": "a b"}}. Values come
 * back percent-decoded; see {@link HttpPathTemplate} for the decoding rules.
 */
@Component
public class GrpcPathMatcher {

    /**
     * @param actualPath the raw request path, still percent-encoded
     */
    public Map<String, String> extractVars(RouteDefinition route, String actualPath) {
        if (!route.hasRestBinding()) return Collections.emptyMap();
        String template = route.getHttpPath();
        if (!HttpPathTemplate.isTemplate(template)) return Collections.emptyMap();
        Map<String, String> vars = HttpPathTemplate.of(template).match(actualPath);
        return vars != null ? vars : Collections.emptyMap();
    }

    public boolean matches(RouteDefinition route, String httpMethod, String actualPath) {
        if (!route.hasRestBinding()) return false;
        if (!route.getHttpMethod().equalsIgnoreCase(httpMethod)) return false;
        String template = route.getHttpPath();
        return HttpPathTemplate.isTemplate(template)
                ? HttpPathTemplate.of(template).matches(actualPath)
                : template.equals(actualPath);
    }
}
