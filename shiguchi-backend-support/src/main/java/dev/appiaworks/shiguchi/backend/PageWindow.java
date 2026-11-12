package dev.appiaworks.shiguchi.backend;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.pagination.PageRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The slice of a collection one request asks for: how many rows, from where, and what to do with
 * the extra row fetched to learn whether another page exists.
 *
 * <pre>{@code
 * PageWindow window = PageWindow.of(request.getPageRequest(), PageDefaults.of(25, 200));
 * List<Book> rows = fetch(window.offset(), window.fetchSize());   // limit + 1 rows
 * PageResult<Book> page = window.trim(rows);
 * response.addAllBooks(page.items()).setPageInfo(page.toPageInfo());
 * }</pre>
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><b>Offset</b> ({@link #of}): the token carries a row offset. Simplest, but it drifts when
 *       rows are inserted or deleted mid-listing and gets slower on deep pages.
 *   <li><b>Keyset</b> ({@link #keyset}): the token carries the last row's sort key, and the next
 *       page is "rows after this key". Stable under writes and flat in cost, but the sort
 *       columns must be non-null and the sort must be the same on every page. {@code
 *       shiguchi-backend-support-jpa}'s {@code ProjectionQuery} does this for you.
 * </ul>
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public final class PageWindow {

    private final int limit;
    private final int offset;
    private final Map<String, String> after;
    private final boolean keyset;
    private final PageCursor cursor;

    private PageWindow(int limit, int offset, Map<String, String> after, boolean keyset, PageCursor cursor) {
        this.limit = limit;
        this.offset = offset;
        this.after = after;
        this.keyset = keyset;
        this.cursor = cursor;
    }

    /** Offset paging with an unsigned token. */
    public static PageWindow of(PageRequest request, PageDefaults defaults) {
        return of(request, defaults, PageCursor.unsigned());
    }

    /** Offset paging with the given cursor codec. */
    public static PageWindow of(PageRequest request, PageDefaults defaults, PageCursor cursor) {
        return new PageWindow(
                defaults.clamp(request.getPageSize()),
                cursor.decodeOffset(request.getPageToken()),
                Map.of(),
                false,
                cursor);
    }

    /** Keyset paging; {@link #after()} holds the decoded key of the last row of the previous page. */
    public static PageWindow keyset(PageRequest request, PageDefaults defaults, PageCursor cursor) {
        return new PageWindow(
                defaults.clamp(request.getPageSize()), 0, cursor.decode(request.getPageToken()), true, cursor);
    }

    /** The page size after applying the defaults and the ceiling. */
    public int limit() {
        return limit;
    }

    /** Rows to skip (offset mode; 0 in keyset mode). */
    public int offset() {
        return offset;
    }

    /** How many rows to ask the store for: one more than the page, to see if there is a next. */
    public int fetchSize() {
        return limit + 1;
    }

    public boolean isKeyset() {
        return keyset;
    }

    /** Keyset mode: the previous page's last key, empty on the first page. */
    public Map<String, String> after() {
        return after;
    }

    /** Offset mode: cuts the extra row and issues the next offset token. */
    public <T> PageResult<T> trim(List<T> rows) {
        if (keyset) {
            throw new IllegalStateException("Keyset paging needs keys: use trim(rows, keyOf)");
        }
        boolean more = rows.size() > limit;
        return new PageResult<>(more ? rows.subList(0, limit) : rows, more ? cursor.encodeOffset(offset + limit) : "");
    }

    /** Keyset mode: cuts the extra row and issues a token carrying the last kept row's key. */
    public <T> PageResult<T> trim(List<T> rows, Function<? super T, Map<String, String>> keyOf) {
        boolean more = rows.size() > limit;
        List<T> items = more ? rows.subList(0, limit) : rows;
        return new PageResult<>(items, more ? cursor.encode(keyOf.apply(items.get(limit - 1))) : "");
    }

    /** Keyset mode with keys already extracted, parallel to {@code rows}. */
    public <T> PageResult<T> trimKeyed(List<T> rows, List<Map<String, String>> keys) {
        boolean more = rows.size() > limit;
        return new PageResult<>(more ? rows.subList(0, limit) : rows, more ? cursor.encode(keys.get(limit - 1)) : "");
    }
}
