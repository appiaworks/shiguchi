package dev.appiaworks.shiguchi.sample.openfga;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.AuthzRuleResolver;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientCheckResponse;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A {@link GatewayAuthorizer} that adds per-object authorization on top of the built-in scope
 * check, using OpenFGA.
 *
 * <h2>What this adds</h2>
 *
 * The default {@link ScopeGatewayAuthorizer} answers "may this caller call {@code GetOrder}?". It
 * cannot answer "may this caller read <em>order 42</em>?", because a JWT scope is a property of the
 * caller alone, not of the caller-object pair. This authorizer runs the scope check first (cheap,
 * in-process, and it rejects anonymous callers before any network cost) and only then asks OpenFGA
 * the object-level question.
 *
 * <h2>How a route becomes a check</h2>
 *
 * <ol>
 *   <li>The route is looked up in {@code sample.openfga.routes} to find its
 *       {@link RouteObjectPermission} — object type, required relation, and which request argument
 *       carries the object id. Routes not listed there get no per-object check.</li>
 *   <li>The object id is read from {@code AuthorizationRequest.arguments()}, which the gateway
 *       populates from REST path variables and query parameters, or GraphQL field arguments.</li>
 *   <li>The caller becomes the FGA user; selected JWT claims become <em>contextual tuples</em>, so
 *       group membership need not be synchronised into OpenFGA ahead of time.</li>
 *   <li>{@code check(user, relation, object)} runs. Allowed continues; anything else raises
 *       {@link AccessDeniedException}, which the gateway renders as HTTP 403.</li>
 * </ol>
 *
 * <h2>Operational safety</h2>
 *
 * The check sits on the request hot path, so it is bounded by a timeout and never blocks — the SDK
 * returns a {@code CompletableFuture}, adapted with {@code Mono.fromFuture}. A failure or timeout
 * resolves according to {@code fail-open}, which defaults to closed.
 *
 * <p>Decisions are optionally cached. The cache key includes a fingerprint of the contextual
 * tuples, not just {@code (user, relation, object)}: two callers with the same subject but
 * different claims must never share an entry, or one inherits the other's access.
 */
public class OpenFgaGatewayAuthorizer implements GatewayAuthorizer {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaGatewayAuthorizer.class);

    /** JWT claim copied into contextual tuples, and the FGA shape it is given. */
    private static final String GROUPS_CLAIM = "groups";

    private static final String GROUP_RELATION = "member";
    private static final String GROUP_OBJECT_TYPE = "group";

    /** OpenFGA rejects more than this many contextual tuples in one request. */
    private static final int MAX_CONTEXTUAL_TUPLES = 100;

    private final ScopeGatewayAuthorizer scopeAuthorizer;
    private final OpenFgaClient fgaClient;
    private final OpenFgaAuthzProperties props;

    /** Decision cache; null when caching is disabled. */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> decisions;

    public OpenFgaGatewayAuthorizer(
            ScopeGatewayAuthorizer scopeAuthorizer, OpenFgaClient fgaClient, OpenFgaAuthzProperties props) {
        this.scopeAuthorizer = scopeAuthorizer;
        this.fgaClient = fgaClient;
        this.props = props;
        this.decisions = props.getCache().isEnabled() ? buildCache(props.getCache()) : null;
    }

    private static com.github.benmanes.caffeine.cache.Cache<String, Boolean> buildCache(
            OpenFgaAuthzProperties.Cache cfg) {
        // Allows and denies expire on different schedules, so expiry is computed per entry rather
        // than with a single expireAfterWrite.
        return Caffeine.newBuilder()
                .maximumSize(cfg.getMaxSize())
                .expireAfter(new Expiry<String, Boolean>() {
                    private long nanosFor(Boolean allowed) {
                        var ttl = Boolean.TRUE.equals(allowed) ? cfg.getTtl() : cfg.getNegativeTtl();
                        return TimeUnit.MILLISECONDS.toNanos(ttl.toMillis());
                    }

                    @Override
                    public long expireAfterCreate(String key, Boolean allowed, long currentTime) {
                        return nanosFor(allowed);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Boolean allowed, long currentTime, long currentDuration) {
                        return nanosFor(allowed);
                    }

                    @Override
                    public long expireAfterRead(String key, Boolean allowed, long currentTime, long currentDuration) {
                        // Reading must not extend a decision's life: the TTL bounds staleness, and
                        // refreshing it on access would let a hot entry never expire.
                        return currentDuration;
                    }
                })
                .build();
    }

    @Override
    public Mono<Void> authorize(AuthorizationRequest request) {
        return scopeAuthorizer.authorize(request).then(Mono.defer(() -> objectCheck(request)));
    }

    private Mono<Void> objectCheck(AuthorizationRequest request) {
        // Rules declared on the proto annotation win: the policy then travels with the service
        // definition. The YAML map remains as a fallback for routes whose protos are not annotated
        // (a third-party backend, or one you cannot rebuild).
        AuthzRuleResolver.Resolved resolved = AuthzRuleResolver.resolve(request);

        if (!resolved.unresolved().isEmpty()) {
            // A declared rule whose id field is absent cannot be evaluated. Denying is the safe
            // reading — an annotation typo or an omitted parameter must not become a bypass.
            var missing = resolved.unresolved().stream()
                    .map(r -> r.idField())
                    .toList();
            return Mono.error(
                    new AccessDeniedException("Cannot authorize: no value for " + missing + " in request"));
        }

        List<Check> checks = new ArrayList<>();
        for (var c : resolved.checks()) {
            checks.add(new Check(c.relation(), c.objectRef()));
        }

        if (checks.isEmpty()) {
            // No annotation rules — fall back to the configured route map.
            RouteObjectPermission permission = permissionFor(request.route());
            if (permission == null) {
                return Mono.empty(); // no object-level rule declared for this route
            }
            Object rawId = request.arguments().get(permission.idArgument());
            if (rawId == null || rawId.toString().isBlank()) {
                return Mono.error(new AccessDeniedException(
                        "Cannot authorize: no value for '" + permission.idArgument() + "' in request"));
            }
            checks.add(new Check(permission.relation(), permission.objectRef(rawId.toString())));
        }

        Authentication auth = request.authentication();
        String user = fgaUser(auth);
        if (user == null) {
            return Mono.error(new AccessDeniedException("Cannot authorize an unauthenticated caller"));
        }

        List<ClientTupleKey> contextual = contextualTuples(auth, user);

        // Every rule must pass. Checks run sequentially and short-circuit on the first denial:
        // a denied call should not spend round trips evaluating the rest.
        return Flux.fromIterable(checks)
                .concatMap(check -> runOne(user, check, contextual))
                .then();
    }

    /** One resolved requirement: the caller must hold {@code relation} on {@code objectRef}. */
    private record Check(String relation, String objectRef) {}

    private Mono<Void> runOne(String user, Check check, List<ClientTupleKey> contextual) {
        String cacheKey = cacheKey(user, check.relation(), check.objectRef(), contextual);

        if (decisions != null) {
            Boolean cached = decisions.getIfPresent(cacheKey);
            if (cached != null) {
                return decide(cached, user, check.relation(), check.objectRef(), true);
            }
        }

        return callFga(user, check.relation(), check.objectRef(), contextual)
                .doOnNext(allowed -> {
                    if (decisions != null) decisions.put(cacheKey, allowed);
                })
                .flatMap(allowed -> decide(allowed, user, check.relation(), check.objectRef(), false));
    }

    private Mono<Boolean> callFga(String user, String relation, String objectRef, List<ClientTupleKey> contextual) {
        return Mono.defer(() -> {
                    ClientCheckRequest check =
                            new ClientCheckRequest().user(user).relation(relation)._object(objectRef);
                    if (!contextual.isEmpty()) {
                        check.contextualTuples(contextual);
                    }
                    try {
                        // The SDK is CompletableFuture-based, so this never blocks the event loop.
                        return Mono.fromFuture(fgaClient.check(check));
                    } catch (Exception e) {
                        // check() declares a checked exception for invalid configuration.
                        return Mono.<ClientCheckResponse>error(e);
                    }
                })
                // getAllowed() is a nullable Boolean; absent means not allowed.
                .map(response -> Boolean.TRUE.equals(response.getAllowed()))
                .timeout(props.getTimeout())
                .onErrorResume(error -> {
                    log.warn(
                            "OpenFGA check failed for {} {} {} — failing {}",
                            user,
                            relation,
                            objectRef,
                            props.isFailOpen() ? "OPEN" : "CLOSED",
                            error);
                    return Mono.just(props.isFailOpen());
                });
    }

    private Mono<Void> decide(boolean allowed, String user, String relation, String objectRef, boolean cached) {
        String suffix = cached ? " (cached)" : "";
        if (allowed) {
            log.debug("OpenFGA allow{}: {} {} {}", suffix, user, relation, objectRef);
            return Mono.empty();
        }
        log.debug("OpenFGA deny{}: {} {} {}", suffix, user, relation, objectRef);
        return Mono.error(new AccessDeniedException("Not permitted: " + relation + " on " + objectRef));
    }

    /** Looks up the object rule: GraphQL by field name, REST by "METHOD /path/template". */
    private RouteObjectPermission permissionFor(RouteDefinition route) {
        if (route.getGraphQlName() != null) {
            RouteObjectPermission byField = props.getRoutes().get(route.getGraphQlName());
            if (byField != null) return byField;
        }
        if (route.getHttpMethod() == null || route.getHttpPath() == null) return null;
        return props.getRoutes().get(route.getHttpMethod() + " " + route.getHttpPath());
    }

    /** The FGA user reference for the caller, or null when there is no usable identity. */
    private String fgaUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return null;
        String name = auth.getName();
        return (name == null || name.isBlank()) ? null : "user:" + name;
    }

    /**
     * Turns JWT group claims into contextual tuples, supplying group membership per request instead
     * of synchronising it into the OpenFGA store.
     */
    private List<ClientTupleKey> contextualTuples(Authentication auth, String user) {
        List<ClientTupleKey> tuples = new ArrayList<>();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) return tuples;

        Jwt jwt = jwtAuth.getToken();
        Object claim = jwt.getClaim(GROUPS_CLAIM);
        if (claim instanceof Collection<?> groups) {
            for (Object group : groups) {
                if (group == null) continue;
                tuples.add(new ClientTupleKey()
                        .user(user)
                        .relation(GROUP_RELATION)
                        ._object(GROUP_OBJECT_TYPE + ":" + group));
                if (tuples.size() >= MAX_CONTEXTUAL_TUPLES) break;
            }
        }
        return tuples;
    }

    /**
     * Cache key. Contextual tuples are part of the key deliberately: the same
     * {@code (user, relation, object)} can resolve differently depending on the claims presented,
     * and collapsing those into one entry would let one caller inherit another's access.
     */
    private String cacheKey(String user, String relation, String objectRef, List<ClientTupleKey> contextual) {
        StringBuilder fingerprint = new StringBuilder();
        contextual.stream()
                .map(t -> t.getUser() + "#" + t.getRelation() + "@" + t.getObject())
                .sorted() // order must not change the key
                .forEach(s -> fingerprint.append(s).append(';'));
        return user + "|" + relation + "|" + objectRef + "|" + fingerprint;
    }

    /** Exposed for tests and diagnostics; null when caching is disabled. */
    com.github.benmanes.caffeine.cache.Cache<String, Boolean> cache() {
        return decisions;
    }
}
