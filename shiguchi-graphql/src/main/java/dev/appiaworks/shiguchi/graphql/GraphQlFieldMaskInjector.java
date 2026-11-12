package dev.appiaworks.shiguchi.graphql;

import com.google.common.base.CaseFormat;
import com.google.protobuf.Descriptors.Descriptor;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

        // A Relay connection selects { edges { node { ... } } pageInfo }: the mask is about the
        // element, so it is built from the node's fields, not from `edges` / `pageInfo`.
        List<SelectedField> nodeFields = env.getSelectionSet().getFields("edges/node/*");
        Stream<String> names = nodeFields.isEmpty()
                ? env.getSelectionSet().getImmediateFields().stream().map(SelectedField::getName)
                : nodeFields.stream().map(SelectedField::getName);
        Set<String> paths = names.map(n -> CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, n))
                .filter(name -> !name.startsWith("__"))
                .filter(name -> nodeFields.isEmpty() ? isMaskable(name, inputDescriptor) : true)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (paths.isEmpty()) return requestMap;

        var withMask = new java.util.HashMap<>(requestMap);
        withMask.put("readMask", Map.of("paths", new ArrayList<>(paths)));
        return withMask;
    }

    private static boolean isMaskable(String name, Descriptor inputDescriptor) {
        return inputDescriptor.findFieldByName(name) != null || !name.startsWith("page") && !name.startsWith("sort");
    }
}
