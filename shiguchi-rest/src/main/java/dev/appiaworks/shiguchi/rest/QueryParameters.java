package dev.appiaworks.shiguchi.rest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request's query parameters as the single-value map the gateway passes around (the first value
 * of each name), which also keeps every value of a name that was repeated.
 *
 * <p>Almost everything reads one value per name — convention parameters, auth arguments, a
 * singular field. A repeated field is the exception: {@code ?tags=a&tags=b} must reach the
 * backend as {@code ["a", "b"]}, one element per occurrence. {@link #all} gives that list
 * for any map, falling back to the single value when the map is not one of these.
 */
public final class QueryParameters extends LinkedHashMap<String, String> {

    private static final long serialVersionUID = 1L;

    private final LinkedHashMap<String, List<String>> values = new LinkedHashMap<>();

    /** Builds the map from name → values, skipping names that carry no value at all. */
    public static QueryParameters of(Map<String, List<String>> raw) {
        QueryParameters params = new QueryParameters();
        raw.forEach((name, list) -> {
            if (list == null || list.isEmpty()) return;
            params.put(name, list.get(0));
            params.values.put(name, Collections.unmodifiableList(new ArrayList<>(list))); // ?flag carries a null
        });
        return params;
    }

    /**
     * Every value sent for {@code name}, in request order; the single mapped value when
     * {@code params} is a plain map or the entry was replaced after construction; empty when absent.
     */
    public static List<String> all(Map<String, String> params, String name) {
        String first = params.get(name);
        if (first == null) return List.of();
        if (params instanceof QueryParameters q) {
            List<String> list = q.values.get(name);
            if (list != null && first.equals(list.get(0))) return list;
        }
        return List.of(first);
    }
}
