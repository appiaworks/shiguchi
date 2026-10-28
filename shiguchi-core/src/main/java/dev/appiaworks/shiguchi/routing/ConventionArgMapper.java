package dev.appiaworks.shiguchi.routing;

import com.google.protobuf.Descriptors.Descriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the loosely-typed paging/sorting arguments both HTTP surfaces accept onto the nested
 * proto fields the conventions define.
 *
 * <p>REST arrives here with query-parameter strings ({@code ?pageSize=25&sort=year:desc});
 * GraphQL arrives with SDL argument values ({@code first: 25, sort: [{field, sortOrder}]}).
 * Both are normalised to the same request-map shape — {@code pageRequest.pageSize},
 * {@code pageRequest.pageToken}, and a {@code sortFields} list — so a backend sees one
 * spelling regardless of which surface the call came in on.
 *
 * <p>Nesting matters: {@code ProtoMapConverter.dynamicFromMap} matches map keys against
 * top-level field names only, so a flat {@code pageToken} key would never reach
 * {@code page_request.page_token}.
 */
public final class ConventionArgMapper {

    public static final String PAGE_REQUEST_FIELD = "page_request";
    public static final String SORT_FIELDS_FIELD = "sort_fields";
    public static final String READ_MASK_FIELD = "read_mask";

    private ConventionArgMapper() {}

    /**
     * Folds {@code pageSize} / {@code first} / {@code pageToken} / {@code after} into a nested
     * {@code pageRequest} entry, removing the flat aliases. No-op when the request message has
     * no {@code page_request} field — the aliases are stripped either way so they never reach
     * the backend as unknown fields.
     */
    public static Map<String, Object> applyPaging(Map<String, Object> args, Descriptor inputDescriptor) {
        boolean hasPageRequest = inputDescriptor.findFieldByName(PAGE_REQUEST_FIELD) != null;

        Integer pageSize = firstInt(args.get("pageSize"), args.get("first"));
        String pageToken = asString(args.get("pageToken"));
        String afterCursor = asString(args.get("after"));

        if (pageSize == null && pageToken == null && afterCursor == null) return args;

        Map<String, Object> out = new HashMap<>(args);
        out.remove("pageSize");
        out.remove("first");
        out.remove("pageToken");
        out.remove("after");

        if (!hasPageRequest) return out;

        Map<String, Object> pageRequest = new HashMap<>();
        if (pageSize != null) pageRequest.put("pageSize", pageSize);
        if (pageToken != null) pageRequest.put("pageToken", pageToken);
        // The Relay cursor wins over an explicit token: it is the more specific argument.
        if (afterCursor != null) pageRequest.put("pageToken", unpackCursor(afterCursor));

        if (!pageRequest.isEmpty()) out.put("pageRequest", pageRequest);
        return out;
    }

    /**
     * Renames the {@code sort} argument to the {@code sortFields} field the convention defines.
     * Accepts either the REST string form ({@code "year:desc,author"}) or the GraphQL list form
     * (a list of {@code {field, sortOrder}} maps), and drops the argument when the request
     * message has no {@code sort_fields} field.
     */
    public static Map<String, Object> applySorting(Map<String, Object> args, Descriptor inputDescriptor) {
        Object sort = args.get("sort");
        if (sort == null) return args;

        Map<String, Object> out = new HashMap<>(args);
        out.remove("sort");

        if (inputDescriptor.findFieldByName(SORT_FIELDS_FIELD) == null) return out;

        List<Map<String, String>> sortFields = sort instanceof String s ? parseSortString(s) : normaliseSortList(sort);
        if (!sortFields.isEmpty()) out.put("sortFields", sortFields);
        return out;
    }

    /** Parses the REST {@code ?sort=field:asc,other:desc} spelling. */
    static List<Map<String, String>> parseSortString(String sortParam) {
        List<Map<String, String>> sortFields = new ArrayList<>();
        for (String part : sortParam.split(",")) {
            String[] kv = part.trim().split(":");
            String field = kv[0].trim();
            if (field.isEmpty()) continue;
            Map<String, String> sf = new HashMap<>();
            sf.put("field", field);
            sf.put("sortOrder", isDescending(kv) ? "SORT_ORDER_DESC" : "SORT_ORDER_ASC");
            sortFields.add(sf);
        }
        return sortFields;
    }

    /**
     * Normalises the GraphQL {@code [SortFieldInput]} spelling, tolerating a bare enum name or a
     * single (non-list) input object.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> normaliseSortList(Object sort) {
        List<?> entries = sort instanceof List<?> list ? list : List.of(sort);
        List<Map<String, String>> sortFields = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> m)) continue;
            Object field = m.get("field");
            if (field == null || field.toString().isBlank()) continue;
            Object order = m.get("sortOrder");
            String orderName = order == null ? "SORT_ORDER_ASC" : order.toString();
            if (!orderName.startsWith("SORT_ORDER_")) {
                orderName = "desc".equalsIgnoreCase(orderName) ? "SORT_ORDER_DESC" : "SORT_ORDER_ASC";
            }
            Map<String, String> sf = new HashMap<>();
            sf.put("field", field.toString());
            sf.put("sortOrder", orderName);
            sortFields.add(sf);
        }
        return sortFields;
    }

    private static boolean isDescending(String[] kv) {
        return kv.length > 1 && "desc".equalsIgnoreCase(kv[1].trim());
    }

    /** Base64 Relay cursor → the page-token component before the {@code ":"} separator. */
    static String unpackCursor(String base64Cursor) {
        try {
            String decoded = new String(
                    java.util.Base64.getDecoder().decode(base64Cursor), java.nio.charset.StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon > 0 ? decoded.substring(0, colon) : decoded;
        } catch (Exception e) {
            return base64Cursor; // treat as a raw token if it is not a synthetic cursor
        }
    }

    private static Integer firstInt(Object... candidates) {
        for (Object c : candidates) {
            if (c instanceof Number n) return n.intValue();
            if (c instanceof String s && !s.isBlank()) {
                try {
                    return Integer.valueOf(s.trim());
                } catch (NumberFormatException ignored) {
                    // fall through to the next candidate
                }
            }
        }
        return null;
    }

    private static String asString(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
