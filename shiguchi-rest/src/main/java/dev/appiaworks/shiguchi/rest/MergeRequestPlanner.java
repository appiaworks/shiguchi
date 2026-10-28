package dev.appiaworks.shiguchi.rest;

import dev.appiaworks.shiguchi.merge.MergeCursor;
import dev.appiaworks.shiguchi.merge.SortComparator;
import dev.appiaworks.shiguchi.routing.ConventionArgMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what each backend in a merge is actually asked for.
 *
 * <p>This is where a source's declared sortedness stops being a flag and becomes a different
 * request on the wire, so it is separated from {@link MergeDispatcher} and tested on its own.
 *
 * <ul>
 *   <li><b>Pre-sorted</b> — the caller's {@code sortFields} are pushed down and the backend is
 *       asked for a bounded window, resuming from its own opaque {@code page_token}. The gateway
 *       reads only as far as the page needs.
 *   <li><b>Unsorted</b> — the sort is dropped (the backend would ignore it anyway) <em>and so is
 *       paging</em>. This is the part that surprises people: asking an unsorted backend for the
 *       first 50 rows returns an arbitrary 50, not the smallest 50, so merging them would order
 *       the wrong records and quietly omit rows that belong on page one. The only correct request
 *       is the whole result set, sorted gateway-side.
 * </ul>
 */
public final class MergeRequestPlanner {

    /** Page size used when the caller does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    private MergeRequestPlanner() {}

    /**
     * Builds the request args for one source.
     *
     * @param requestArgs the caller's normalised args (filters, sort, paging)
     * @param position this source's resume state, or null on the first page
     * @param presorted whether the backend orders its own output
     * @param window how many records a pre-sorted source may be asked for
     */
    public static Map<String, Object> argsForSource(
            Map<String, Object> requestArgs, MergeCursor.SourcePosition position, boolean presorted, int window) {

        Map<String, Object> args = new HashMap<>(requestArgs);
        args.remove("pageRequest");
        args.remove(ConventionArgMapper.PAGE_REQUEST_FIELD);

        if (!presorted) {
            // No sort to push down, and no safe way to page — see the class javadoc.
            args.remove("sortFields");
            return args;
        }

        Map<String, Object> pageRequest = new HashMap<>();
        pageRequest.put("pageSize", window);
        if (position != null
                && !position.token().isEmpty()
                && !MergeCursor.MATERIALISED_MARKER.equals(position.token())) {
            pageRequest.put("pageToken", position.token());
        }
        args.put("pageRequest", pageRequest);
        return args;
    }

    /** Reads the caller's requested page size, falling back to {@link #DEFAULT_PAGE_SIZE}. */
    public static int pageSizeOf(Map<String, Object> requestArgs) {
        if (requestArgs.get("pageRequest") instanceof Map<?, ?> pr && pr.get("pageSize") instanceof Number n) {
            return n.intValue() > 0 ? n.intValue() : DEFAULT_PAGE_SIZE;
        }
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * Parses the normalised {@code sortFields} list into comparator keys, preserving caller order
     * so the first key is the primary sort. An unspecified direction is ascending, matching
     * {@code SORT_ORDER_UNSPECIFIED}.
     */
    public static List<SortComparator.SortKey> sortKeysOf(Map<String, Object> requestArgs) {
        List<SortComparator.SortKey> keys = new ArrayList<>();
        if (requestArgs.get("sortFields") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m && m.get("field") != null) {
                    keys.add(new SortComparator.SortKey(
                            m.get("field").toString(), !"SORT_ORDER_DESC".equals(String.valueOf(m.get("sortOrder")))));
                }
            }
        }
        return keys;
    }

    /** The merge window: a page plus room to see past a tie straddling the page boundary. */
    public static int windowFor(int pageSize, int configuredWindow) {
        return configuredWindow > 0 ? configuredWindow : Math.max(pageSize * 3, pageSize + 1);
    }
}
