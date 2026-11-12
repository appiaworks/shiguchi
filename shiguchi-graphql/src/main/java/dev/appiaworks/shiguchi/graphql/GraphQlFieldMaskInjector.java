package dev.appiaworks.shiguchi.graphql;

import com.google.common.base.CaseFormat;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import graphql.schema.DataFetchingEnvironment;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Derives google.protobuf.FieldMask paths from the GraphQL selection set and
 * injects them into the proto request JSON.
 *
 * Only applied when the input Descriptor has a "read_mask" field.
 * Converts GraphQL camelCase field names to proto snake_case paths.
 * Enables projection pushdown: backends that respect read_mask skip computing
 * unrequested fields, reducing latency and database load.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlFieldMaskInjector {

    /**
     * As {@link #inject(Map, Descriptor, DataFetchingEnvironment)}, but aware of the response
     * shape. When the response is exposed as a Relay connection ({@code edges { node { ... } }}),
     * the mask paths are the node's own fields ({@code title}), relative to the item, the same
     * as REST's {@code ?fields=}, not the connection's top-level fields ({@code edges}), which
     * would mask every real field away.
     */
    public Map<String, Object> inject(
            Map<String, Object> requestMap,
            Descriptor inputDescriptor,
            Descriptor outputDescriptor,
            DataFetchingEnvironment env) {
        if (inputDescriptor.findFieldByName("read_mask") == null) return requestMap;
        var items = outputDescriptor.getFields().stream()
                .filter(f -> f.isRepeated() && f.getType() == FieldDescriptor.Type.MESSAGE)
                .findFirst()
                .orElse(null);
        if (items == null) return inject(requestMap, inputDescriptor, env);

        Set<String> paths = env.getSelectionSet().getFields("edges/node/*").stream()
                .filter(f -> f.getQualifiedName().split("/").length == 3)
                .map(f -> CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, f.getName()))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (paths.isEmpty()) return requestMap;

        var withMask = new java.util.HashMap<>(requestMap);
        withMask.put("readMask", Map.of("paths", new ArrayList<>(paths)));
        return withMask;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> inject(
            Map<String, Object> requestMap, Descriptor inputDescriptor, DataFetchingEnvironment env) {

        if (inputDescriptor.findFieldByName("read_mask") == null) return requestMap;

        Set<String> paths = env.getSelectionSet().getImmediateFields().stream()
                .map(f -> CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, f.getName()))
                .filter(name -> inputDescriptor.findFieldByName(name) != null
                        || !name.startsWith("page") && !name.startsWith("sort"))
                .collect(Collectors.toSet());

        if (paths.isEmpty()) return requestMap;

        var withMask = new java.util.HashMap<>(requestMap);
        withMask.put("readMask", Map.of("paths", new ArrayList<>(paths)));
        return withMask;
    }
}
