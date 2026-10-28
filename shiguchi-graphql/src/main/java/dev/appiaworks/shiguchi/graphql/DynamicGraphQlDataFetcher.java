package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.ConventionArgMapper;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Metadata;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * DGS DataFetcher that routes GraphQL operations to the appropriate gRPC backend reactively.
 *
 * Returns CompletableFuture<Object> for unary and server-streaming-as-list operations,
 * or Flux<Object> (Publisher) for SUBSCRIPTION routes so graphql-java can push each
 * gRPC stream item to subscribers individually.
 *
 * Token relay: the metadata is built by {@link GrpcMetadataPopulator} exactly as for REST (principal,
 * Authorization or the credential source's, forward-headers, tracing), from the exchange in the
 * GraphQL context. The request's Reactor context is restored around every chain
 * ({@link GraphQlRequestContext}), because the returned future subscribes outside it.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class DynamicGraphQlDataFetcher implements DataFetcher<Object> {

    private static final Logger log = LoggerFactory.getLogger(DynamicGraphQlDataFetcher.class);

    private final GrpcRouteRegistry registry;
    private final ReactiveGrpcDispatcher dispatcher;
    private final RelayConnectionTransformer relayTransformer;
    private final GraphQlFieldMaskInjector fieldMaskInjector;
    private final GraphQlSelectionProjector selectionProjector;
    private final GrpcMetadataPopulator metadataPopulator;
    private final GatewayAuthorizationEnforcer authEnforcer;
    private final dev.appiaworks.shiguchi.rest.MergeDispatcher mergeDispatcher;
    private final dev.appiaworks.shiguchi.rest.HttpUpstreamDispatcher httpDispatcher;

    @Autowired
    public DynamicGraphQlDataFetcher(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            RelayConnectionTransformer relayTransformer,
            GraphQlFieldMaskInjector fieldMaskInjector,
            GraphQlSelectionProjector selectionProjector,
            GrpcMetadataPopulator metadataPopulator,
            GatewayAuthorizationEnforcer authEnforcer,
            dev.appiaworks.shiguchi.rest.MergeDispatcher mergeDispatcher,
            dev.appiaworks.shiguchi.rest.HttpUpstreamDispatcher httpDispatcher) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.relayTransformer = relayTransformer;
        this.fieldMaskInjector = fieldMaskInjector;
        this.selectionProjector = selectionProjector;
        this.metadataPopulator = metadataPopulator;
        this.authEnforcer = authEnforcer;
        this.mergeDispatcher = mergeDispatcher;
        this.httpDispatcher = httpDispatcher;
    }

    /** Previous constructor shape, without HTTP passthrough support. Kept for callers that wire by hand. */
    public DynamicGraphQlDataFetcher(
            GrpcRouteRegistry registry,
            ReactiveGrpcDispatcher dispatcher,
            RelayConnectionTransformer relayTransformer,
            GraphQlFieldMaskInjector fieldMaskInjector,
            GraphQlSelectionProjector selectionProjector,
            GrpcMetadataPopulator metadataPopulator,
            GatewayAuthorizationEnforcer authEnforcer,
            dev.appiaworks.shiguchi.rest.MergeDispatcher mergeDispatcher) {
        this(
                registry,
                dispatcher,
                relayTransformer,
                fieldMaskInjector,
                selectionProjector,
                metadataPopulator,
                authEnforcer,
                mergeDispatcher,
                null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object get(DataFetchingEnvironment env) {
        String fieldName = env.getField().getName();

        var routes = registry.findByGraphQlName(fieldName);
        if (routes.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No route for GraphQL field: " + fieldName));
        }

        RouteDefinition route = routes.get(0);
        ServiceEndpointContext ctx = registry.contextFor(route);

        Map<String, Object> args = env.getArguments();

        MethodDescriptor md = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName())
                .orElse(null);
        if (md != null) {
            args = fieldMaskInjector.inject(args, md.getInputType(), env);
        } else {
            log.warn(
                    "[GraphQL] No MethodDescriptor for {}/{} — check staticServiceClasses config",
                    route.getFullServiceName(),
                    route.getMethodName());
        }
        args = unwrapInputArg(args);
        if (md != null) {
            // Map the SDL paging/sorting args (first/after/sort) onto the nested proto fields the
            // conventions define — the same mapping the REST surface applies to its query params.
            args = ConventionArgMapper.applyPaging(args, md.getInputType());
            args = ConventionArgMapper.applySorting(args, md.getInputType());
        }

        log.debug("[GraphQL→] {} → {}/{} args={}", fieldName, route.getFullServiceName(), route.getMethodName(), args);
        final long startNs = System.nanoTime();

        final MethodDescriptor finalMd = md;
        final Map<String, Object> finalArgs = args;

        // Convert only the composite subtrees the query selected (scalars always convert).
        final dev.appiaworks.protobuf.extras.serde.ProjectionNode projection =
                finalMd != null ? selectionProjector.build(env, route, finalMd.getOutputType()) : null;

        // A merge route fans out to several backends and returns one assembled page, so it bypasses
        // the single-backend dispatch below on both the streaming and unary paths. The sources may
        // individually be server-streaming; that is the merge's business, not this fetcher's.
        if (route.isMerge()) {
            return GraphQlRequestContext.within(
                            env.getGraphQlContext(),
                            authEnforcer
                                    .enforce(route, finalArgs, null)
                                    .then(buildMetadataFromEnv(env))
                                    .flatMap(metadata ->
                                            mergeDispatcher.dispatchToMaps(route, finalArgs, metadata, projection)))
                    .map(records -> records.stream()
                            .map(record -> (Object) applyResponseTransforms(record, route))
                            .toList())
                    .doOnSuccess(items -> log.debug("[GraphQL← merge] {} items={}", fieldName, items.size()))
                    .toFuture();
        }

        boolean isServerStreaming = finalMd != null && finalMd.isServerStreaming();
        boolean isSubscription = route.getGraphQlOperationType() == RouteDefinition.GraphQlOperationType.SUBSCRIPTION;

        if (isServerStreaming) {
            Flux<Object> flux = GraphQlRequestContext.within(
                            env.getGraphQlContext(),
                            authEnforcer
                                    .enforce(route, finalArgs, null)
                                    .then(buildMetadataFromEnv(env))
                                    .flatMapMany(metadata -> dispatcher.dispatchStreamingToMap(
                                            route, ctx, finalArgs, metadata, projection)))
                    .map(item -> applyResponseTransforms(item, route))
                    .doOnNext(item -> log.debug("[GraphQL← stream] {} item={}", fieldName, item))
                    .doOnError(t -> log.warn(
                            "[GraphQL← stream ERROR] field={} service={}/{} error={}",
                            fieldName,
                            route.getFullServiceName(),
                            route.getMethodName(),
                            t.getMessage(),
                            t))
                    .onErrorMap(t -> wrapBackendError(t, route, ctx));

            if (isSubscription) {
                // Return Publisher directly — graphql-java pushes each item to WebSocket subscribers.
                return flux;
            }
            // Collect all stream items into a list for server-streaming queries.
            return flux.collectList().map(list -> (Object) list).toFuture();
        }

        // Enforce declared auth scopes, then build gRPC metadata and dispatch. A REST_DOWNSTREAM
        // route goes over HTTP instead of the channel; the Map it returns has the same shape.
        // Composed reactively so nothing blocks Netty threads.
        return GraphQlRequestContext.within(
                        env.getGraphQlContext(),
                        authEnforcer
                                .enforce(route, finalArgs, null)
                                .then(buildMetadataFromEnv(env))
                                .flatMap(metadata -> route.isPassthrough()
                                        ? httpDispatch(route, ctx, finalArgs, metadata, projection)
                                        : dispatcher.dispatchToMap(route, ctx, finalArgs, metadata, projection)))
                .map(response -> {
                    // Response pluck: extract a single named field and return it directly
                    String responsePluck = route.getGraphQlResponsePluck();
                    if (responsePluck != null && !responsePluck.isEmpty()) {
                        Object plucked = response.get(responsePluck);
                        return plucked != null ? plucked : (Object) response;
                    }

                    if (finalMd != null) {
                        Descriptor outputDesc = finalMd.getOutputType();
                        if (relayTransformer.isConnectionCandidate(outputDesc)) {
                            // The cursor this page was asked for with. hasPreviousPage cannot be
                            // answered from the response alone — a backend that returns only
                            // next_page_token has said nothing about what came before — but a
                            // caller that sent `after` is by definition past the first page.
                            Object after = finalArgs.get("after");
                            response = relayTransformer.transform(
                                    response,
                                    relayTransformer.getEdgesFieldName(outputDesc),
                                    after instanceof String cursor ? cursor : null);
                        }
                    }
                    return (Object) response;
                })
                .doOnSuccess(result -> log.debug(
                        "[GraphQL←] {} ({}ms) response={}",
                        fieldName,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
                        result))
                .doOnError(t -> log.warn(
                        "[GraphQL← ERROR] field={} service={}/{} elapsed={}ms",
                        fieldName,
                        route.getFullServiceName(),
                        route.getMethodName(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
                        t))
                .onErrorMap(t -> wrapBackendError(t, route, ctx))
                .toFuture();
    }

    private Mono<Map<String, Object>> httpDispatch(
            RouteDefinition route,
            ServiceEndpointContext ctx,
            Map<String, Object> args,
            io.grpc.Metadata metadata,
            dev.appiaworks.protobuf.extras.serde.ProjectionNode projection) {
        if (httpDispatcher == null) {
            return Mono.error(new IllegalStateException("Route " + route.getGraphQlName()
                    + " is an HTTP passthrough but no HttpUpstreamDispatcher is wired"));
        }
        return httpDispatcher.dispatchToMap(route, ctx, args, metadata, projection);
    }

    /** The outbound metadata, built the way the REST surface builds it: see {@link GraphQlMetadata}. */
    private Mono<Metadata> buildMetadataFromEnv(DataFetchingEnvironment env) {
        return GraphQlMetadata.build(env.getGraphQlContext(), metadataPopulator);
    }

    private Object applyResponseTransforms(Map<String, Object> item, RouteDefinition route) {
        String responsePluck = route.getGraphQlResponsePluck();
        if (responsePluck != null && !responsePluck.isEmpty()) {
            Object plucked = item.get(responsePluck);
            return plucked != null ? plucked : item;
        }
        return item;
    }

    /**
     * When the schema builder wraps query args in an input type (e.g. input: FooInput),
     * the GraphQL runtime delivers { "input": { "field": value } }. The proto request
     * expects the fields at the top level, so we unwrap the single "input" entry.
     * Pagination/relay args alongside "input" are preserved as-is.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapInputArg(Map<String, Object> args) {
        Object inputVal = args.get("input");
        if (!(inputVal instanceof Map<?, ?> inputMap)) return args;
        var unwrapped = new java.util.HashMap<String, Object>(args);
        unwrapped.remove("input");
        unwrapped.putAll((Map<String, Object>) inputMap);
        return unwrapped;
    }

    /**
     * A gRPC failure becomes a {@link GrpcBackendException} carrying the status and the backend's
     * error details, which {@link GrpcGraphQlExceptionHandler} renders; anything else is named
     * after the route so the error can be traced to a backend.
     */
    private static Throwable wrapBackendError(Throwable t, RouteDefinition route, ServiceEndpointContext ctx) {
        if (t instanceof io.grpc.StatusRuntimeException sre) {
            return new GrpcBackendException(
                    route.getFullServiceName(), route.getMethodName(), sre, ctx == null ? null : ctx.getTypeRegistry());
        }
        String svc = route.getFullServiceName() + "/" + route.getMethodName();
        String msg = t.getMessage();
        return msg != null && msg.contains(svc) ? t : new IllegalStateException("[" + svc + "] " + msg, t);
    }
}
