package dev.appiaworks.shiguchi.rest;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.MergeRouteConfig;
import dev.appiaworks.shiguchi.config.MergeSourceConfig;
import dev.appiaworks.shiguchi.merge.MergeSource;
import dev.appiaworks.shiguchi.merge.MergeSpec;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves how a route is declared to be a merge.
 *
 * <p>Three sources, first match wins:
 *
 * <ol>
 *   <li>a {@link MergeSourceProvider} bean — for topologies computed at runtime
 *   <li>{@code shiguchi.merge.routes} in YAML — topology as a deployment concern
 *   <li>the proto annotation — the merge travels with the API definition
 * </ol>
 *
 * <p>All three resolve to the same {@link MergeSpec}, so the dispatcher has one input shape and
 * none of the declaration mechanisms can drift in behaviour from the others.
 *
 * <p>Validation happens here, at registration, so a malformed merge fails at startup with the
 * offending route named rather than on a caller's request.
 */
@Component
public class MergeSpecResolver {

    private static final Logger log = LoggerFactory.getLogger(MergeSpecResolver.class);

    private final GatewayProperties properties;
    private final List<MergeSourceProvider> providers;
    private final dev.appiaworks.shiguchi.routing.GrpcRouteRegistry registry;

    public MergeSpecResolver(
            GatewayProperties properties,
            List<MergeSourceProvider> providers,
            dev.appiaworks.shiguchi.routing.GrpcRouteRegistry registry) {
        this.properties = properties;
        this.providers = providers != null ? providers : List.of();
        this.registry = registry;
    }

    /** @return the merge to serve this route with, or empty for an ordinary route */
    public Optional<MergeSpec> resolve(RouteDefinition route) {
        return resolve(route, null);
    }

    /**
     * @param ctx the context the route is being registered into, or null to look one up. Route
     *     loaders pass theirs: resolution happens <em>before</em> {@code registerContext}, so the
     *     registry cannot yet answer for this service, and an annotation read through it would
     *     silently never match.
     */
    public Optional<MergeSpec> resolve(
            RouteDefinition route, dev.appiaworks.shiguchi.routing.ServiceEndpointContext ctx) {
        for (MergeSourceProvider provider : providers) {
            Optional<MergeSpec> fromSpi = provider.mergeFor(route);
            if (fromSpi.isPresent()) {
                return fromSpi.map(spec -> validate(spec, route, "MergeSourceProvider"));
            }
        }
        var yaml = fromYaml(route);
        if (yaml.isPresent()) {
            return yaml.map(spec -> validate(spec, route, "YAML"));
        }
        return fromAnnotation(route, ctx).map(spec -> validate(spec, route, "proto annotation"));
    }

    /**
     * Reads {@code (shiguchi.api.merge)} off the route's own method descriptor.
     *
     * <p>Lowest precedence of the three, because it is the least dynamic: a merge baked into the
     * .proto travels with the API definition, which is what you want for a fixed topology and not
     * what you want when the sources vary by deployment.
     */
    private Optional<MergeSpec> fromAnnotation(
            RouteDefinition route, dev.appiaworks.shiguchi.routing.ServiceEndpointContext ctx) {
        var owning = ctx != null
                ? ctx
                : registry.contextByServiceFqn(route.getFullServiceName()).orElse(null);
        if (owning == null) {
            return Optional.empty();
        }
        var md = owning.lookupMethod(route.getFullServiceName(), route.getMethodName())
                .orElse(null);
        if (md == null) {
            return Optional.empty();
        }
        var annotation = md.getOptions().getExtension(dev.appiaworks.shiguchi.proto.api.MergeProto.merge);
        if (annotation == null || annotation.getSourcesCount() == 0) {
            return Optional.empty();
        }

        List<MergeSource> sources = new ArrayList<>(annotation.getSourcesCount());
        for (dev.appiaworks.shiguchi.proto.api.MergeSource declared : annotation.getSourcesList()) {
            sources.add(new MergeSource(
                    declared.getEndpoint(),
                    declared.getFullServiceName(),
                    declared.getMethodName(),
                    declared.getPresorted()));
        }
        return Optional.of(new MergeSpec(
                sources,
                annotation.getTieBreakField(),
                strategyOrDefault(annotation.getTotalCountStrategy()),
                policyOrDefault(annotation.getFailurePolicy()),
                annotation.getWindowSize()));
    }

    /** Proto strings are free-form, so an unrecognised value falls back rather than failing. */
    private MergeSpec.TotalCountStrategy strategyOrDefault(String value) {
        try {
            return value == null || value.isBlank()
                    ? MergeSpec.TotalCountStrategy.UNKNOWN
                    : MergeSpec.TotalCountStrategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown total_count_strategy '{}' in a merge annotation; using UNKNOWN", value);
            return MergeSpec.TotalCountStrategy.UNKNOWN;
        }
    }

    private MergeSpec.FailurePolicy policyOrDefault(String value) {
        try {
            return value == null || value.isBlank()
                    ? MergeSpec.FailurePolicy.FAIL_FAST
                    : MergeSpec.FailurePolicy.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown failure_policy '{}' in a merge annotation; using FAIL_FAST", value);
            return MergeSpec.FailurePolicy.FAIL_FAST;
        }
    }

    private Optional<MergeSpec> fromYaml(RouteDefinition route) {
        if (!route.hasRestBinding()) {
            return Optional.empty();
        }
        for (MergeRouteConfig cfg : properties.getMerge().getRoutes()) {
            if (route.getHttpPath().equals(cfg.getPath())
                    && route.getHttpMethod().equalsIgnoreCase(cfg.getMethod())) {
                return Optional.of(toSpec(cfg));
            }
        }
        return Optional.empty();
    }

    private MergeSpec toSpec(MergeRouteConfig cfg) {
        List<MergeSource> sources = new ArrayList<>(cfg.getSources().size());
        for (MergeSourceConfig source : cfg.getSources()) {
            sources.add(
                    new MergeSource(source.getEndpoint(), source.getService(), source.getRpc(), source.isPresorted()));
        }
        return new MergeSpec(
                sources,
                cfg.getTieBreakField(),
                MergeSpec.TotalCountStrategy.valueOf(cfg.getTotalCountStrategy()),
                MergeSpec.FailurePolicy.valueOf(cfg.getFailurePolicy()),
                cfg.getWindowSize());
    }

    /**
     * Rejects merges that would misbehave later, and warns about the ones that are legal but
     * probably not what the author meant.
     */
    private MergeSpec validate(MergeSpec spec, RouteDefinition route, String origin) {
        String where =
                route.hasRestBinding() ? route.getHttpMethod() + " " + route.getHttpPath() : route.getGraphQlName();

        int max = properties.getMerge().getMaxSources();
        if (spec.sources().size() > max) {
            throw new IllegalStateException("merge route %s (%s) declares %d sources, over the limit of %d — raise "
                            .formatted(where, origin, spec.sources().size(), max)
                    + "shiguchi.merge.max-sources if this is intended");
        }

        List<String> labels = spec.sources().stream().map(MergeSource::label).toList();
        if (labels.size() != labels.stream().distinct().count()) {
            throw new IllegalStateException("merge route %s (%s) lists the same source twice: %s — every row would "
                            .formatted(where, origin, labels)
                    + "appear once per duplicate entry");
        }

        if (spec.tieBreakField().isBlank()) {
            log.warn(
                    "Merge route {} ({}) declares no tie-break-field. If the caller's sort key is not unique, a page "
                            + "boundary landing inside a group of tied records cannot tell which were already served, "
                            + "so pages may repeat or drop rows. Set tie-break-field to a stable unique field (usually "
                            + "the id).",
                    where,
                    origin);
        }

        if (spec.totalCountStrategy() == MergeSpec.TotalCountStrategy.SUM) {
            log.warn(
                    "Merge route {} ({}) uses total-count-strategy: SUM. This is only correct when the sources hold "
                            + "disjoint rows — overlapping id spaces will double-count.",
                    where,
                    origin);
        }

        for (MergeSource source : spec.sources()) {
            if (!source.presorted()) continue;
            boolean http = registry.findBySourceMethod(source.endpoint(), source.fullServiceName(), source.methodName())
                    .map(RouteDefinition::isPassthrough)
                    .orElse(false);
            if (http) {
                log.warn(
                        "Merge route {} ({}) declares HTTP source {} as presorted. A REST_DOWNSTREAM backend cannot "
                                + "be asked to sort or page in the merge's terms, so it is read in full and sorted "
                                + "in the gateway on every page; the flag is ignored.",
                        where,
                        origin,
                        source.label());
            }
        }

        long unsorted = spec.sources().stream().filter(s -> !s.presorted()).count();
        if (unsorted > 0) {
            log.info(
                    "Merge route {} ({}) has {} of {} sources declared unsorted; each is read in full and sorted in "
                            + "the gateway on every page. Teaching those backends to honour sort_fields lets them "
                            + "stream instead. See docs/merging.md.",
                    where,
                    origin,
                    unsorted,
                    spec.sources().size());
        }
        return spec;
    }
}
