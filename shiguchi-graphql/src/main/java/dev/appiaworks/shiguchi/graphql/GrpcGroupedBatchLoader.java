package dev.appiaworks.shiguchi.graphql;

/**
 * The gRPC form of {@link GroupedBatchLoader}: the call, its timeout and its error mapping are
 * {@link GrpcBatchLoader}'s ({@code loadItems}), so a grouped loader fails and times out exactly as a
 * by-id one.
 */
public class GrpcGroupedBatchLoader extends GroupedBatchLoader {

    public GrpcGroupedBatchLoader(GrpcBatchLoader source, String groupByJsonName, int maxChildrenPerKey) {
        super(source::loadItems, groupByJsonName, maxChildrenPerKey);
    }
}
