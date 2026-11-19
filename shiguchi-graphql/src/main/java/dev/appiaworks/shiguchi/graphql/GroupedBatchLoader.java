package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * One-to-many batch loader over any transport: one call for a list of parent keys, whose response
 * entities are grouped by the field that holds the parent key ({@code group_by}). A parent with no
 * entities maps to an empty list, never to absent. {@link GrpcGroupedBatchLoader} (gRPC) and the REST
 * loader of {@link RestBatchDataLoaderRegistrar} are this over their own {@link ItemSource}.
 *
 * <p>The call, its timeout and its error mapping are the source's, so a grouped loader fails and
 * times out exactly as the by-id one of the same transport. Entities keep the order the backend sent
 * them in within a parent.
 *
 * <p>{@code maxChildrenPerKey} ({@code data-loader.max-children-per-key}, 0 = unlimited) fails a
 * parent that has more children than that: its key fails, the other keys of the batch load. The
 * response is decoded before the cap is checked, so it protects the GraphQL response, not the
 * memory spent decoding the upstream response.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.26.0")
public class GroupedBatchLoader implements GroupedOutcome.Loader {

    /** The flat call under a grouped loader: the entities of every parent key, in one list. */
    @FunctionalInterface
    public interface ItemSource {
        Mono<List<Map<String, Object>>> loadItems(List<String> keys, Metadata metadata);
    }

    private final ItemSource source;
    private final String groupByJsonName;
    private final int maxChildrenPerKey;

    public GroupedBatchLoader(ItemSource source, String groupByJsonName, int maxChildrenPerKey) {
        this.source = source;
        this.groupByJsonName = groupByJsonName;
        this.maxChildrenPerKey = maxChildrenPerKey;
    }

    @Override
    public Mono<GroupedOutcome> loadGrouped(List<String> keys, Metadata metadata) {
        return source.loadItems(keys, metadata).map(items -> group(keys, items));
    }

    private GroupedOutcome group(List<String> keys, List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>(keys.size() * 4 / 3 + 1);
        for (String key : keys) groups.put(key, new ArrayList<>());
        for (Map<String, Object> item : items) {
            Object parent = item.get(groupByJsonName);
            if (parent == null) continue;
            List<Map<String, Object>> children = groups.get(String.valueOf(parent));
            // An entity of a parent nobody asked for is not ours to return
            if (children != null) children.add(item);
        }
        if (maxChildrenPerKey <= 0) return new GroupedOutcome(groups, Map.of());
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (var entry : List.copyOf(groups.entrySet())) {
            int count = entry.getValue().size();
            if (count > maxChildrenPerKey) {
                groups.remove(entry.getKey());
                failures.put(
                        entry.getKey(),
                        new IllegalStateException("Key " + entry.getKey() + " has " + count
                                + " children, more than shiguchi.graphql.data-loader.max-children-per-key ("
                                + maxChildrenPerKey + ")"));
            }
        }
        return new GroupedOutcome(groups, failures);
    }
}
