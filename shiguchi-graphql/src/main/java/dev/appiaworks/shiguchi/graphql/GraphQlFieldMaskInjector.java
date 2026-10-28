package dev.appiaworks.shiguchi.graphql;

import com.google.common.base.CaseFormat;
import com.google.protobuf.Descriptors.Descriptor;
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
