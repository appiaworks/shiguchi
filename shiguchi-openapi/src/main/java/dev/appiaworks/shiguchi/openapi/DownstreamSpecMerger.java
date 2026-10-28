package dev.appiaworks.shiguchi.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges a {@code REST_DOWNSTREAM} service's own OpenAPI document into the gateway's consolidated
 * spec, so Swagger UI shows the proxied operations under the paths the gateway actually serves.
 *
 * <p>What changes on the way in:
 * <ul>
 *   <li><b>Paths</b> are prefixed with the service's {@code path-prefix}. Operations hidden by
 *       {@code include-methods} / {@code exclude-methods} (those with no registered route) are
 *       dropped, so the document never advertises something the gateway returns 404 for.</li>
 *   <li><b>Component names</b> are namespaced as {@code <service>.<Name>} and every {@code $ref}
 *       rewritten to match. Two backends both defining {@code Error} or {@code Page} is the normal
 *       case; without this the second would silently be dropped by a first-wins merge.</li>
 *   <li><b>operationId</b> and <b>tags</b> are prefixed with the service name, for the same reason
 *       and so Swagger UI groups each backend in its own section.</li>
 *   <li><b>servers</b> are removed at every level. The gateway is the server now.</li>
 *   <li><b>securitySchemes</b> and <b>security</b> requirements are removed unless the service sets
 *       {@code inherit-security: true}; the gateway's configured schemes drive the Authorize dialog.</li>
 *   <li>Vendor extensions ({@code x-*}) are preserved untouched.</li>
 * </ul>
 *
 * <p>Renaming happens on the JSON tree before it is bound to the swagger model, which is the only
 * place a {@code $ref} can be rewritten regardless of where it sits (schemas, parameters,
 * responses, request bodies, headers, links, callbacks).
 */
public final class DownstreamSpecMerger {

    private static final Logger log = LoggerFactory.getLogger(DownstreamSpecMerger.class);
    private static final String COMPONENTS_REF = "#/components/";

    private DownstreamSpecMerger() {}

    /**
     * Merges the context's raw document into {@code targetPaths} and {@code targetComponents}.
     * A path already present in the target is left as is and logged.
     */
    public static void merge(ServiceEndpointContext ctx, Paths targetPaths, Components targetComponents) {
        String service = ctx.getConfig().getName();
        String prefix = ctx.getConfig().getPathPrefix() != null
                ? ctx.getConfig().getPathPrefix().trim()
                : "";
        boolean inheritSecurity = ctx.getConfig().isInheritSecurity();

        OpenAPI doc;
        try {
            doc = parse(ctx.getRawDownstreamSpec(), ctx.getDownstreamSpecMediaType(), service, inheritSecurity);
        } catch (Exception e) {
            log.warn("Skipping OpenAPI merge for REST_DOWNSTREAM service '{}': {}", service, e.getMessage());
            return;
        }

        Set<String> exposed = new HashSet<>();
        for (RouteDefinition route : ctx.getRoutes()) {
            if (route.hasRestBinding()) exposed.add(key(route.getHttpMethod(), route.getHttpPath()));
        }

        int merged = 0;
        if (doc.getPaths() != null) {
            for (Map.Entry<String, PathItem> entry : doc.getPaths().entrySet()) {
                String fullPath = prefix + entry.getKey();
                PathItem item = entry.getValue();
                if (item == null) continue;
                item.setServers(null);

                for (Map.Entry<PathItem.HttpMethod, Operation> opEntry :
                        item.readOperationsMap().entrySet()) {
                    Operation op = opEntry.getValue();
                    if (!exposed.contains(key(opEntry.getKey().name(), fullPath))) {
                        item.operation(opEntry.getKey(), null);
                        continue;
                    }
                    op.setServers(null);
                    if (!inheritSecurity) op.setSecurity(null);
                    if (op.getOperationId() != null && !op.getOperationId().isBlank()) {
                        op.setOperationId(service + "_" + op.getOperationId());
                    }
                    op.setTags(prefixTags(service, op.getTags()));
                    merged++;
                }
                if (item.readOperationsMap().isEmpty()) continue;

                if (targetPaths.containsKey(fullPath)) {
                    log.warn(
                            "REST_DOWNSTREAM service '{}' declares path {} which is already in the consolidated "
                                    + "spec; keeping the existing entry",
                            service,
                            fullPath);
                    continue;
                }
                targetPaths.addPathItem(fullPath, item);
            }
        }

        if (doc.getComponents() != null) {
            copyComponents(doc.getComponents(), targetComponents, inheritSecurity);
        }
        log.debug("Merged {} operations from REST_DOWNSTREAM service '{}' into the OpenAPI spec", merged, service);
    }

    /**
     * Parses raw bytes into a swagger model after namespacing every component name and
     * {@code $ref}, stripping servers, and (unless inherited) removing security. Exposed for
     * tests; production goes through {@link #merge}.
     */
    static OpenAPI parse(byte[] raw, String mediaType, String service, boolean inheritSecurity) throws Exception {
        boolean yaml = "application/yaml".equalsIgnoreCase(mediaType);
        ObjectMapper reader = yaml ? Yaml.mapper() : Json.mapper();
        JsonNode root = reader.readTree(raw);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("document root is not an object");
        }
        ObjectNode rootObj = (ObjectNode) root;

        rootObj.remove("servers");
        if (!inheritSecurity) rootObj.remove("security");

        JsonNode components = rootObj.get("components");
        if (components instanceof ObjectNode compObj) {
            if (!inheritSecurity) compObj.remove("securitySchemes");
            List<Map.Entry<String, JsonNode>> renamed = new ArrayList<>();
            for (Map.Entry<String, JsonNode> kind : compObj.properties()) {
                if (!(kind.getValue() instanceof ObjectNode kindObj)) continue;
                ObjectNode replacement = kindObj.objectNode();
                for (Map.Entry<String, JsonNode> e : kindObj.properties()) {
                    replacement.set(service + "." + e.getKey(), e.getValue());
                }
                renamed.add(Map.entry(kind.getKey(), replacement));
            }
            renamed.forEach(e -> compObj.set(e.getKey(), e.getValue()));
        }

        rewriteRefs(rootObj, service);

        boolean v31 = rootObj.path("openapi").asText("").startsWith("3.1");
        ObjectMapper binder = v31 ? (yaml ? Yaml31.mapper() : Json31.mapper()) : reader;
        return binder.treeToValue(rootObj, OpenAPI.class);
    }

    /** Rewrites every {@code #/components/<kind>/<Name>} reference to {@code <service>.<Name>}. */
    private static void rewriteRefs(JsonNode node, String service) {
        if (node instanceof ObjectNode obj) {
            JsonNode ref = obj.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith(COMPONENTS_REF)) {
                String rest = ref.asText().substring(COMPONENTS_REF.length());
                int slash = rest.indexOf('/');
                if (slash > 0 && slash < rest.length() - 1) {
                    String kind = rest.substring(0, slash);
                    String name = rest.substring(slash + 1);
                    obj.set("$ref", new TextNode(COMPONENTS_REF + kind + "/" + service + "." + name));
                }
            }
            for (Map.Entry<String, JsonNode> e : obj.properties()) rewriteRefs(e.getValue(), service);
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(n -> rewriteRefs(n, service));
        }
    }

    private static List<String> prefixTags(String service, List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of(service);
        List<String> out = new ArrayList<>(tags.size());
        for (String t : tags) out.add(service + " / " + t);
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void copyComponents(Components from, Components to, boolean inheritSecurity) {
        copy(from.getSchemas(), to::getSchemas, to::setSchemas);
        copy(from.getResponses(), to::getResponses, to::setResponses);
        copy(from.getParameters(), to::getParameters, to::setParameters);
        copy(from.getExamples(), to::getExamples, to::setExamples);
        copy(from.getRequestBodies(), to::getRequestBodies, to::setRequestBodies);
        copy(from.getHeaders(), to::getHeaders, to::setHeaders);
        copy(from.getLinks(), to::getLinks, to::setLinks);
        copy(from.getCallbacks(), to::getCallbacks, to::setCallbacks);
        if (inheritSecurity) {
            copy(from.getSecuritySchemes(), to::getSecuritySchemes, to::setSecuritySchemes);
        }
    }

    private static <T> void copy(
            Map<String, T> source,
            Supplier<Map<String, T>> getter,
            java.util.function.Consumer<Map<String, T>> setter) {
        if (source == null || source.isEmpty()) return;
        Map<String, T> target = getter.get();
        if (target == null) {
            target = new LinkedHashMap<>();
            setter.accept(target);
        }
        for (Map.Entry<String, T> e : source.entrySet()) target.putIfAbsent(e.getKey(), e.getValue());
    }

    private static String key(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }
}
