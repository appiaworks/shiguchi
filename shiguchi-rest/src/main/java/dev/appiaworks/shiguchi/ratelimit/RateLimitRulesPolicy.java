package dev.appiaworks.shiguchi.ratelimit;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayPolicy;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.config.InboundRateLimitConfig;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Rule-based rate limiting as a {@link GatewayPolicy} ({@code shiguchi.rate-limit.rules[]}).
 *
 * <p>Every rule whose {@code match} (glob on {@code <service>/<Method>}) and {@code tag} (exact)
 * both fit the route applies; each takes {@code cost} tokens from its own bucket for the caller's
 * key. When one or more refuse, the request is throttled with the longest {@code retryAfter}
 * (the strictest rule). All matching buckets are charged, including when another rule refuses.
 * A limiter that errors is handled per rule by {@code shiguchi.rate-limit.rules-on-backend-error}
 * (default {@code ALLOW}: fail open and log; {@code DENY}: throttle with {@code Retry-After: 1}).
 * The Redis limiter handles its own outage first, per {@code redis.fail-open}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.25.0")
public class RateLimitRulesPolicy implements GatewayPolicy {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRulesPolicy.class);

    private final List<CompiledRule> rules;
    private final String clientIpHeader;
    private final boolean failOpen;

    /**
     * @param rules the configured rules (validated here: startup fails on a bad one)
     * @param defaultReplenishRate used by a rule without {@code replenish-rate}
     * @param defaultBurstCapacity used by a rule without {@code burst-capacity}
     * @param clientIpHeader header whose first value is the client IP, or empty
     * @param limiters builds the limiter of each rule
     */
    public RateLimitRulesPolicy(
            List<InboundRateLimitConfig.Rule> rules,
            double defaultReplenishRate,
            int defaultBurstCapacity,
            String clientIpHeader,
            RuleLimiterFactory limiters) {
        this(rules, defaultReplenishRate, defaultBurstCapacity, clientIpHeader, limiters, null);
    }

    /**
     * @param onBackendError what a rule does when its limiter errors; {@code null} means
     *     {@link InboundRateLimitConfig.BackendError#ALLOW}
     */
    public RateLimitRulesPolicy(
            List<InboundRateLimitConfig.Rule> rules,
            double defaultReplenishRate,
            int defaultBurstCapacity,
            String clientIpHeader,
            RuleLimiterFactory limiters,
            InboundRateLimitConfig.BackendError onBackendError) {
        this.failOpen = onBackendError != InboundRateLimitConfig.BackendError.DENY;
        this.clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank() ? null : clientIpHeader;
        List<CompiledRule> compiled = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            CompiledRule rule = compile(i, rules.get(i), defaultReplenishRate, defaultBurstCapacity, limiters);
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("shiguchi.rate-limit.rules: duplicate id '" + rule.id() + "'");
            }
            compiled.add(rule);
        }
        this.rules = List.copyOf(compiled);
    }

    @Override
    public Mono<Void> check(AuthorizationRequest request) {
        RouteDefinition route = request.route();
        List<CompiledRule> matching =
                rules.stream().filter(r -> r.matches(route)).toList();
        if (matching.isEmpty()) return Mono.empty();
        return Flux.fromIterable(matching)
                .concatMap(rule -> Mono.defer(
                                () -> rule.limiter().tryAcquire(rule.id() + "|" + key(rule, request), rule.cost()))
                        .map(decision -> decision.allowed() ? 0L : Math.max(1L, decision.retryAfterSeconds()))
                        .onErrorResume(e -> Mono.just(backendFailure(rule, e))))
                .reduce(0L, Math::max)
                .flatMap(retryAfter -> retryAfter > 0
                        ? Mono.<Void>error(new ThrottledException(Duration.ofSeconds(retryAfter)))
                        : Mono.<Void>empty());
    }

    /** Same wording and outcome as {@link RedisRateLimiter}: allow (0) or refuse with retry-after 1. */
    private long backendFailure(CompiledRule rule, Throwable cause) {
        String reason = cause.getMessage();
        if (failOpen) {
            log.warn(
                    "Rate-limit rule '{}' cannot reach its limiter ({}); allowing the request (fail-open)",
                    rule.id(),
                    reason,
                    cause);
            return 0L;
        }
        log.warn(
                "Rate-limit rule '{}' cannot reach its limiter ({}); refusing the request (fail-closed)",
                rule.id(),
                reason,
                cause);
        return 1L;
    }

    private String key(CompiledRule rule, AuthorizationRequest request) {
        Authentication auth = request.authentication();
        String principal = principal(auth);
        return switch (rule.keyKind()) {
            case IP -> ip(request);
            case PRINCIPAL -> principal != null ? "user:" + principal : ip(request);
            case CLAIM -> {
                String claim = claim(auth, rule.claim());
                yield claim != null ? "claim:" + claim : principal != null ? "user:" + principal : ip(request);
            }
        };
    }

    private static String principal(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return null;
        String name = auth.getName();
        return name == null || name.isBlank() || "anonymousUser".equals(name) ? null : name;
    }

    private static String claim(Authentication auth, String name) {
        if (principal(auth) == null) return null;
        Object value = null;
        if (auth instanceof JwtAuthenticationToken jwt) {
            value = jwt.getToken().getClaims().get(name);
        } else if (auth.getPrincipal() instanceof Jwt jwt) {
            value = jwt.getClaims().get(name);
        }
        String text = value == null ? null : String.valueOf(value);
        return text == null || text.isBlank() ? null : text;
    }

    private String ip(AuthorizationRequest request) {
        if (clientIpHeader != null && request.headers() != null) {
            List<String> values = request.headers().get(clientIpHeader);
            if (values != null
                    && !values.isEmpty()
                    && values.get(0) != null
                    && !values.get(0).isBlank()) {
                String value = values.get(0);
                int comma = value.indexOf(',');
                return "ip:" + (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        // The policy sees no socket peer: callers without the header share one bucket.
        return "ip:unknown";
    }

    // ---- compilation ----

    private enum KeyKind {
        PRINCIPAL,
        IP,
        CLAIM
    }

    private record CompiledRule(
            String id, Pattern method, String tag, KeyKind keyKind, String claim, int cost, RateLimiter limiter) {

        boolean matches(RouteDefinition route) {
            if (method != null
                    && !method.matcher(route.getFullServiceName() + "/" + route.getMethodName())
                            .matches()) {
                return false;
            }
            return tag == null || route.getTags().contains(tag);
        }
    }

    private static CompiledRule compile(
            int index,
            InboundRateLimitConfig.Rule rule,
            double defaultRate,
            int defaultBurst,
            RuleLimiterFactory limiters) {
        String id = rule.getId() == null || rule.getId().isBlank()
                ? "rule-" + index
                : rule.getId().trim();
        String where = "shiguchi.rate-limit.rules[" + index + "] ('" + id + "')";
        String match = blankToNull(rule.getMatch());
        String tag = blankToNull(rule.getTag());
        if (match == null && tag == null) throw new IllegalArgumentException(where + ": set match, tag or both");
        double rate = rule.getReplenishRate() != null ? rule.getReplenishRate() : defaultRate;
        int burst = rule.getBurstCapacity() != null ? rule.getBurstCapacity() : defaultBurst;
        if (!(rate > 0)) throw new IllegalArgumentException(where + ": replenish-rate must be positive");
        if (burst < 1) throw new IllegalArgumentException(where + ": burst-capacity must be at least 1");
        if (rule.getCost() < 1 || rule.getCost() > burst) {
            throw new IllegalArgumentException(where + ": cost must be between 1 and burst-capacity (" + burst + ")");
        }
        String key = rule.getKey() == null || rule.getKey().isBlank()
                ? "principal"
                : rule.getKey().trim();
        KeyKind kind;
        String claim = null;
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.equals("principal")) {
            kind = KeyKind.PRINCIPAL;
        } else if (lower.equals("ip")) {
            kind = KeyKind.IP;
        } else if (lower.startsWith("claim:") && key.length() > "claim:".length()) {
            kind = KeyKind.CLAIM;
            claim = key.substring("claim:".length()).trim();
        } else {
            throw new IllegalArgumentException(
                    where + ": key must be principal, ip or claim:<name>, not '" + key + "'");
        }
        log.info(
                "Rate-limit rule '{}': match={} tag={} key={} {} req/s, bursts of {}, cost {}",
                id,
                match,
                tag,
                key,
                rate,
                burst,
                rule.getCost());
        return new CompiledRule(
                id,
                match == null ? null : glob(match),
                tag,
                kind,
                claim,
                rule.getCost(),
                limiters.create(id, rate, burst));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** {@code *} matches any run of characters, {@code ?} one; everything else is literal. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*' || c == '?') {
                if (literal.length() > 0) regex.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) regex.append(Pattern.quote(literal.toString()));
        return Pattern.compile(regex.toString());
    }
}
