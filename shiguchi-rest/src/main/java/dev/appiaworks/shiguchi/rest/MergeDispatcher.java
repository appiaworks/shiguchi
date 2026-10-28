package dev.appiaworks.shiguchi.rest;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import dev.appiaworks.shiguchi.merge.HeapMerge;
import dev.appiaworks.shiguchi.merge.MergeCursor;
import dev.appiaworks.shiguchi.merge.MergeCursorCodec;
import dev.appiaworks.shiguchi.merge.MergeSpec;
import dev.appiaworks.shiguchi.merge.SortComparator;
import dev.appiaworks.shiguchi.routing.ConventionArgMapper;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Serves one route by merging several backend RPCs that return the same message type.
 *
 * <p>Fans out to every source in the route's {@link MergeSpec}, orders the results by the caller's
 * {@code sortFields}, and returns one page. Callers cannot tell the result spans more than one
 * backend: they send the same {@code ?sort=} and {@code ?pageSize=} they would send any list
 * endpoint, and get back one ordered page plus a cursor.
 *
 * <h2>Two execution modes, chosen per source</h2>
 *
 * A k-way merge is a merge <em>of sorted sequences</em>. Whether a source can stream depends
 * entirely on whether its backend orders its own output:
 *
 * <ul>
 *   <li><b>Pre-sorted source</b> — the gateway passes {@code sortFields} down and streams the
 *       response, holding one record per source. Memory is O(sources) whatever the result size, and
 *       the merge stops as soon as the page fills.
 *   <li><b>Unsorted source</b> — the gateway buffers that source in full and sorts it before
 *       merging. Correct at any size, but memory scales with the source's entire result set.
 * </ul>
 *
 * <p>Mixing is fine and is the normal migration path: pre-sorted backends stream while one
 * laggard is materialised. The heap only needs each input ordered, not how it got that way.
 *
 * <p>The gateway cannot verify a backend actually honoured {@code sortFields} — it can only send
 * the field and read what comes back — so {@code presorted} is a claim the route makes, and the
 * default is {@code false}. Declaring an unsorted source pre-sorted does not fail loudly; it
 * silently returns misordered pages.
 */
@Component
public class MergeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MergeDispatcher.class);

    /** Fallback page size when the caller does not ask for one. */
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final GrpcRouteRegistry registry;
    private final ReactiveGrpcDispatcher dispatcher;
    private final HttpUpstreamDispatcher httpDispatcher;
    private final dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector;

    @org.springframework.beans.factory.annotation.Autowired
    public MergeDispatcher(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            org.springframework.beans.factory.ObjectProvider<HttpUpstreamDispatcher> httpDispatcher,
            dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector) {
        this(registry, dispatcher, httpDispatcher.getIfAvailable(), serdeSelector);
    }

    /**
     * @param httpDispatcher serves {@code REST_DOWNSTREAM} sources; null when no HTTP backend can
     *     be a source, in which case naming one in a merge spec fails at request time
     */
    public MergeDispatcher(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            HttpUpstreamDispatcher httpDispatcher,
            dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.httpDispatcher = httpDispatcher;
        this.serdeSelector = serdeSelector;
    }

    /** gRPC-only merges. */
    public MergeDispatcher(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            dev.appiaworks.shiguchi.serde.SerdeSelector serdeSelector) {
        this(registry, dispatcher, (HttpUpstreamDispatcher) null, serdeSelector);
    }

    /**
     * Runs the merge and returns one page.
     *
     * @param route the merge route; must carry a {@link MergeSpec}
     * @param requestArgs normalised request args, including {@code sortFields} and {@code
     *     pageRequest} as {@link ConventionArgMapper} produces them for both surfaces
     */
    public Mono<MergePage> dispatch(RouteDefinition route, Map<String, Object> requestArgs, Metadata metadata) {
        MergeSpec spec = route.getMergeSpec();
        if (spec == null) {
            return Mono.error(new IllegalStateException("not a merge route: " + route.getHttpPath()));
        }

        final int pageSize = MergeRequestPlanner.pageSizeOf(requestArgs);
        final List<SortComparator.SortKey> sortKeys = MergeRequestPlanner.sortKeysOf(requestArgs);

        MergeCursor cursor;
        try {
            cursor = cursorOf(requestArgs, spec.sources().size());
        } catch (MergeCursorCodec.InvalidCursorException e) {
            return Mono.error(e);
        }

        Descriptor responseType;
        Comparator<Message> comparator;
        try {
            responseType = responseTypeOf(route, spec);
            comparator = SortComparator.build(responseType, sortKeys, spec.tieBreakField());
        } catch (Exception e) {
            return Mono.error(e);
        }

        // Ask each source for a page's worth plus room to see past a tie straddling the boundary.
        int window = MergeRequestPlanner.windowFor(pageSize, spec.windowSize());

        List<Mono<List<Message>>> sourceCalls = new ArrayList<>(spec.sources().size());
        for (int i = 0; i < spec.sources().size(); i++) {
            var source = spec.sources().get(i);
            MergeCursor.SourcePosition position =
                    cursor == null ? null : cursor.sources().get(i);

            if (position != null && position.exhausted()) {
                sourceCalls.add(Mono.just(List.of()));
                continue;
            }

            RouteDefinition sourceRoute = registry.findBySourceMethod(
                            source.endpoint(), source.fullServiceName(), source.methodName())
                    .orElse(null);
            if (sourceRoute == null) {
                return Mono.error(new IllegalStateException("merge source not registered: " + source.label()
                        + " (declared on " + route.getHttpPath() + ")"));
            }

            // An HTTP source cannot be told to sort or page in the merge's terms — its query
            // parameters are whatever its OpenAPI document declares — so it is always read whole
            // and ordered here, whatever the spec claims. See docs/merging.md.
            boolean presorted = source.presorted() && !sourceRoute.isPassthrough();
            Map<String, Object> args = MergeRequestPlanner.argsForSource(requestArgs, position, presorted, window);
            var ctx = registry.contextFor(sourceRoute);

            Flux<Message> stream = (sourceRoute.isPassthrough()
                            ? httpSource(sourceRoute, ctx, args, metadata, source)
                            : dispatcher.dispatchStreamingMessages(sourceRoute, ctx, args, metadata))
                    .onErrorResume(e -> {
                        if (spec.failurePolicy() == MergeSpec.FailurePolicy.BEST_EFFORT) {
                            log.warn(
                                    "[merge] source {} failed, continuing without it: {}",
                                    source.label(),
                                    e.toString());
                            return Flux.empty();
                        }
                        return Flux.error(new IllegalStateException("merge source failed: " + source.label(), e));
                    });

            // A pre-sorted source only needs enough records to fill the window; an unsorted one has
            // to be read in full before anything about its ordering is known.
            sourceCalls.add(presorted ? stream.take(window).collectList() : stream.collectList());
        }

        final MergeCursor resumeFrom = cursor;
        // Mono.zip, not Flux.concat: the sources are independent and must go out together. Running
        // them in sequence would make a merge as slow as the sum of its backends instead of the
        // slowest one, which is the whole reason to fan out.
        return Mono.zip(sourceCalls, results -> {
                    List<List<Message>> perSource = new ArrayList<>(results.length);
                    for (Object r : results) {
                        @SuppressWarnings("unchecked")
                        List<Message> records = (List<Message>) r;
                        perSource.add(records);
                    }
                    return perSource;
                })
                .map(perSource -> assemble(perSource, comparator, pageSize, resumeFrom, spec));
    }

    /** Merges the fetched sources, cuts one page, and mints the cursor for the next. */
    private MergePage assemble(
            List<List<Message>> perSource,
            Comparator<Message> comparator,
            int pageSize,
            MergeCursor resumeFrom,
            MergeSpec spec) {

        // mergeLists sorts each input defensively, so a source that claimed to be pre-sorted but
        // was not still merges correctly here — the cost is already paid once it is in memory.
        List<Message> merged = HeapMerge.mergeLists(perSource, comparator);

        // Drop what earlier pages already served. A backend resumed by its own page_token has
        // already skipped its share, but a materialised source starts from the top every time.
        if (resumeFrom != null) {
            merged = skipServed(merged, resumeFrom);
        }

        boolean more = merged.size() > pageSize;
        List<Message> page = more ? new ArrayList<>(merged.subList(0, pageSize)) : merged;

        String nextCursor = null;
        if (more && !page.isEmpty()) {
            // Cumulative rows consumed, spread over the source slots so the cursor keeps its
            // per-source shape. The whole count rides on the first slot: with materialised sources
            // it is one merged offset, not a position inside each backend.
            int consumedSoFar = (resumeFrom == null
                            ? 0
                            : resumeFrom.sources().stream()
                                    .mapToInt(MergeCursor.SourcePosition::emittedAtLastKey)
                                    .sum())
                    + page.size();
            List<MergeCursor.SourcePosition> positions = new ArrayList<>(perSource.size());
            for (int i = 0; i < perSource.size(); i++) {
                positions.add(new MergeCursor.SourcePosition(
                        perSource.get(i).isEmpty() ? "" : MergeCursor.MATERIALISED_MARKER, i == 0 ? consumedSoFar : 0));
            }
            nextCursor = MergeCursorCodec.encode(new MergeCursor("offset", positions));
        }

        long total = spec.totalCountStrategy() == MergeSpec.TotalCountStrategy.SUM
                ? perSource.stream().mapToLong(List::size).sum()
                : -1;

        return new MergePage(page, nextCursor, total);
    }

    /**
     * Drops the records earlier pages already served.
     *
     * <p>Position is carried as a plain count of rows consumed, not as a rendered sort key. An
     * earlier version encoded the boundary record's fields into a string and compared those
     * lexically — but string order and the caller's sort order are different orderings, so the
     * comparison disagreed with the merge and rows near a page boundary came back on both pages.
     *
     * <p>A count is exact here because every source is materialised and the merge is deterministic:
     * the same request rebuilds the same ordered list, so "skip the first N" lands precisely where
     * the previous page stopped. It is also why this path re-reads its sources per page — the cost
     * documented in docs/merging.md.
     */
    private List<Message> skipServed(List<Message> merged, MergeCursor cursor) {
        int consumed = cursor.sources().stream()
                .mapToInt(MergeCursor.SourcePosition::emittedAtLastKey)
                .sum();
        return consumed >= merged.size() ? List.of() : new ArrayList<>(merged.subList(consumed, merged.size()));
    }

    private Flux<Message> httpSource(
            RouteDefinition sourceRoute,
            dev.appiaworks.shiguchi.routing.ServiceEndpointContext ctx,
            Map<String, Object> args,
            Metadata metadata,
            dev.appiaworks.shiguchi.merge.MergeSource source) {
        if (httpDispatcher == null) {
            return Flux.error(new IllegalStateException("merge source " + source.label()
                    + " is an HTTP backend but no HttpUpstreamDispatcher is wired to the merge"));
        }
        return httpDispatcher.dispatchMessages(sourceRoute, ctx, args, metadata);
    }

    /**
     * The type one merged row has, taken from the first source: a streaming method's message for
     * gRPC, the {@code items} element type for an HTTP list operation. The other sources' rows may
     * be different descriptor instances; the comparator resolves fields per record.
     */
    private Descriptor responseTypeOf(RouteDefinition route, MergeSpec spec) {
        var first = spec.sources().get(0);
        RouteDefinition sourceRoute = registry.findBySourceMethod(
                        first.endpoint(), first.fullServiceName(), first.methodName())
                .orElseThrow(() -> new IllegalStateException("merge source not registered: " + first.label()));
        var ctx = registry.contextFor(sourceRoute);
        var method = ctx.lookupMethod(first.fullServiceName(), first.methodName())
                .orElseThrow(() -> new IllegalStateException("merge source method not found: " + first.label()));
        return sourceRoute.isPassthrough()
                ? HttpUpstreamDispatcher.rowTypeOf(
                        method, ctx.httpBindingFor(first.methodName()).orElse(null))
                : method.getOutputType();
    }

    private MergeCursor cursorOf(Map<String, Object> requestArgs, int sourceCount) {
        if (requestArgs.get("pageRequest") instanceof Map<?, ?> pr
                && pr.get("pageToken") instanceof String token
                && !token.isBlank()) {
            return MergeCursorCodec.decode(token, sourceCount);
        }
        return null;
    }

    /**
     * Runs the merge and returns the page as Maps, for the GraphQL surface.
     *
     * <p>Conversion lives here rather than in the data fetcher so both surfaces get identical
     * records out of a merge — the REST path serialises the same messages to JSON.
     */
    public Mono<List<Map<String, Object>>> dispatchToMaps(
            RouteDefinition route,
            Map<String, Object> requestArgs,
            Metadata metadata,
            dev.appiaworks.protobuf.extras.serde.ProjectionNode projection) {

        return dispatch(route, requestArgs, metadata).map(page -> {
            var ctx = contextForFirstSource(route);
            List<Map<String, Object>> out = new ArrayList<>(page.records().size());
            for (Message record : page.records()) {
                try {
                    // Merge records are DynamicMessage on the dynamic path and a generated type on
                    // Tier 1; only the former can be projected, so the latter converts wholesale.
                    out.add(
                            record instanceof com.google.protobuf.DynamicMessage dynamic
                                    ? serdeSelector.serializeToMapProjected(
                                            dynamic, record.getDescriptorForType(), ctx, projection)
                                    : serdeSelector.serializeToMap(
                                            com.google.protobuf.DynamicMessage.parseFrom(
                                                    record.getDescriptorForType(), record.toByteString()),
                                            record.getDescriptorForType(),
                                            ctx));
                } catch (Exception e) {
                    throw new IllegalStateException("failed to convert a merged record", e);
                }
            }
            return out;
        });
    }

    private dev.appiaworks.shiguchi.routing.ServiceEndpointContext contextForFirstSource(RouteDefinition route) {
        var first = route.getMergeSpec().sources().get(0);
        var sourceRoute = registry.findBySourceMethod(first.endpoint(), first.fullServiceName(), first.methodName())
                .orElseThrow(() -> new IllegalStateException("merge source not registered: " + first.label()));
        return registry.contextFor(sourceRoute);
    }

    /**
     * One merged page.
     *
     * @param records the page, already ordered
     * @param nextPageToken cursor for the following page, or null when this is the last
     * @param totalCount merged total, or -1 for "not computed" — the honest default, since summing
     *     is only correct when the sources hold disjoint rows
     */
    public record MergePage(List<Message> records, String nextPageToken, long totalCount) {}
}
