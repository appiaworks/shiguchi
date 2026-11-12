package dev.appiaworks.shiguchi.backend;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.pagination.PageInfo;
import java.util.List;

/**
 * One page of results and the token for the next, from {@link PageWindow#trim}.
 *
 * @param items at most {@code limit} items
 * @param nextPageToken blank on the last page, which is how REST callers detect the end
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public record PageResult<T>(List<T> items, String nextPageToken) {

    public boolean hasMore() {
        return !nextPageToken.isEmpty();
    }

    /** The {@code page_info} for the response, without a {@code total_count}. */
    public PageInfo toPageInfo() {
        return PageInfo.newBuilder().setNextPageToken(nextPageToken).build();
    }

    /**
     * The {@code page_info} with a {@code total_count}. Counting costs a query on every request, so
     * call this only when a caller needs the total.
     */
    public PageInfo toPageInfo(long totalCount) {
        return PageInfo.newBuilder()
                .setNextPageToken(nextPageToken)
                .setTotalCount((int) Math.min(totalCount, Integer.MAX_VALUE))
                .build();
    }
}
