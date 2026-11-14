package dev.appiaworks.shiguchi.bff;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

/**
 * Trades a session's access token for one the provider issues for the backend being called (RFC 8693
 * token exchange), for providers whose {@code upstream-token-mode} is {@code EXCHANGE}.
 *
 * <p>Results are cached in this process, per (session, session version, audience, scope), until
 * the token's {@code expires_in} minus the refresh skew; concurrent calls for one key share one
 * request. A refresh replaces the session's access token and bumps its version, so an exchanged
 * token made from the old one is never served again. Nothing is stored: after a failover the next
 * call exchanges once more.
 *
 * <p>A token is never put in an exception message or a log line: failures carry only the issuer's
 * error code, and only when it is a plain token.
 */
final class BffTokenExchanger {

    private static final Logger log = LoggerFactory.getLogger(BffTokenExchanger.class);

    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    /** Form parameters the gateway sets; {@code exchange.extra-parameters} cannot replace them. */
    static final Set<String> PROTOCOL_PARAMETERS = Set.of(
            "grant_type",
            "subject_token",
            "subject_token_type",
            "requested_token_type",
            "audience",
            "scope",
            "actor_token",
            "actor_token_type",
            "client_id",
            "client_secret",
            "client_assertion",
            "client_assertion_type");

    /** Issuer error codes that mean "this caller may not have that token" (403), not "the issuer is unwell". */
    private static final Set<String> REFUSALS = Set.of("invalid_target", "access_denied", "invalid_grant");

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Duration DEFAULT_LIFETIME = Duration.ofSeconds(60);
    private static final long MAX_ENTRIES = 10_000;

    /** The issuer, or this gateway's configuration, says the caller may not have the token (HTTP 403). */
    static final class ExchangeRefused extends RuntimeException {
        ExchangeRefused(String message) {
            super(message);
        }
    }

    /** The exchange could not be done: timeout, 5xx, a refused client, a malformed answer (HTTP 503). */
    static final class ExchangeUnavailable extends RuntimeException {
        ExchangeUnavailable(String message) {
            super(message);
        }
    }

    private record Key(String provider, String session, long version, String audience, String scope) {}

    private final BffProviders providers;
    private final BffProperties properties;
    private final Duration timeout;
    private final Cache<Key, Mono<String>> cache = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    BffTokenExchanger(BffProviders providers, BffProperties properties) {
        this(providers, properties, BffTokenRefresher.TOKEN_ENDPOINT_TIMEOUT);
    }

    BffTokenExchanger(BffProviders providers, BffProperties properties, Duration timeout) {
        this.providers = providers;
        this.properties = properties;
        this.timeout = timeout;
    }

    /** Whether any provider sends exchanged tokens upstream. */
    boolean anyEnabled() {
        return properties.getProviders().values().stream().anyMatch(BffTokenExchanger::exchanges);
    }

    /** Whether the provider sends exchanged tokens upstream. */
    boolean enabled(String providerId) {
        BffProperties.Provider provider =
                providerId == null ? null : properties.getProviders().get(providerId);
        return provider != null && exchanges(provider);
    }

    private static boolean exchanges(BffProperties.Provider provider) {
        return provider.getUpstreamTokenMode() == BffProperties.UpstreamTokenMode.EXCHANGE;
    }

    /**
     * Startup problems of a provider's {@code exchange} block, one line each; empty unless the provider's
     * mode is {@code EXCHANGE}.
     */
    static List<String> problems(String id, BffProperties.Provider provider) {
        List<String> problems = new ArrayList<>();
        if (!exchanges(provider)) return problems;
        BffProperties.Exchange exchange = provider.getExchange();
        String prefix = "provider '" + id + "': ";
        if (exchange == null
                || ((exchange.getTargets() == null || exchange.getTargets().isEmpty())
                        && exchange.getDefaultTarget() == null)) {
            problems.add(prefix + "upstream-token-mode EXCHANGE needs exchange.targets or exchange.default-target");
            return problems;
        }
        if (exchange.getDefaultTarget() != null)
            targetProblem(prefix, "exchange.default-target", exchange.getDefaultTarget(), problems);
        if (exchange.getTargets() != null) {
            exchange.getTargets().forEach((service, target) -> {
                if (service == null || service.isBlank()) {
                    problems.add(prefix + "exchange.targets keys are gRPC service full names, not blank");
                }
                targetProblem(prefix, "exchange.targets." + service, target, problems);
            });
        }
        if (exchange.getExtraParameters() != null) {
            exchange.getExtraParameters().forEach((name, value) -> {
                if (name == null || name.isBlank() || value == null) {
                    problems.add(prefix + "exchange.extra-parameters entries need a name and a value");
                } else if (PROTOCOL_PARAMETERS.contains(name)) {
                    problems.add(
                            prefix + "exchange.extra-parameters must not set '" + name + "': the gateway sends it");
                }
            });
        }
        return problems;
    }

    private static void targetProblem(
            String prefix, String where, BffProperties.ExchangeTarget target, List<String> problems) {
        if (target == null || (blank(target.getAudience()) && blank(target.getScope()))) {
            problems.add(prefix + where + " needs an audience or a scope");
        }
    }

    /**
     * The access token the provider issues for {@code serviceName}, for the session. Errors with
     * {@link ExchangeRefused} or {@link ExchangeUnavailable}.
     *
     * @param session the session whose current access token is the subject
     * @param serviceName the full gRPC service name of the call, or {@code null} when it is not known
     */
    Mono<String> exchange(BffSession session, String serviceName) {
        BffProperties.Provider provider = properties.getProviders().get(session.provider());
        if (provider == null || !exchanges(provider)) {
            return Mono.error(new ExchangeUnavailable("provider " + session.provider() + " does not exchange tokens"));
        }
        BffProperties.ExchangeTarget target;
        try {
            target = target(provider.getExchange(), serviceName);
        } catch (ExchangeRefused refused) {
            return Mono.error(refused);
        }
        Key key =
                new Key(session.provider(), session.key(), session.version(), target.getAudience(), target.getScope());
        Mono<String> exchanged = cache.get(
                key,
                k -> request(session, provider.getExchange(), target)
                        .cache(token -> lifetime(token, provider), error -> Duration.ZERO, () -> Duration.ZERO)
                        .map(Issued::accessToken));
        return exchanged;
    }

    private static BffProperties.ExchangeTarget target(BffProperties.Exchange exchange, String serviceName) {
        BffProperties.ExchangeTarget target =
                serviceName == null ? null : exchange.getTargets().get(serviceName);
        if (target == null) target = exchange.getDefaultTarget();
        if (target == null) {
            log.warn("token exchange: no target for service {} and no default-target", serviceName);
            throw new ExchangeRefused("no exchange target for the service");
        }
        return target;
    }

    private record Issued(String accessToken, Duration lifetime) {}

    private Duration lifetime(Issued issued, BffProperties.Provider provider) {
        Duration left = issued.lifetime().minus(skew());
        return left.isNegative() ? Duration.ZERO : left;
    }

    private Duration skew() {
        return properties.getRefreshSkew();
    }

    private Mono<Issued> request(
            BffSession session, BffProperties.Exchange exchange, BffProperties.ExchangeTarget target) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE);
        form.add("subject_token", session.accessToken());
        form.add("subject_token_type", ACCESS_TOKEN_TYPE);
        form.add("requested_token_type", ACCESS_TOKEN_TYPE);
        if (!blank(target.getAudience())) form.add("audience", target.getAudience());
        if (!blank(target.getScope())) form.add("scope", target.getScope());
        if (exchange.getExtraParameters() != null) exchange.getExtraParameters().forEach(form::add);
        String provider = session.provider();
        return providers
                .tokenEndpointPost(provider, form, timeout)
                .onErrorMap(
                        error -> !(error instanceof ExchangeRefused) && !(error instanceof ExchangeUnavailable),
                        error -> {
                            // The cause's message could echo a URL or a body; the type says enough.
                            log.warn(
                                    "token exchange at provider {} failed: {}",
                                    provider,
                                    error.getClass().getSimpleName());
                            return new ExchangeUnavailable("the token endpoint did not answer the exchange");
                        })
                .flatMap(answer -> answer(provider, answer));
    }

    private Mono<Issued> answer(String provider, BffProviders.TokenAnswer answer) {
        int status = answer.status();
        if (status >= 200 && status < 300) return success(provider, answer.body());
        String code = errorCode(answer.body());
        boolean refusal = (status == 400 || status == 401 || status == 403) && code != null && REFUSALS.contains(code);
        log.warn("token exchange at provider {} answered {} {}", provider, status, code == null ? "" : code);
        if (refusal) return Mono.error(new ExchangeRefused("the issuer refused the token exchange: " + code));
        return Mono.error(new ExchangeUnavailable(
                "the token endpoint answered " + status + (code == null ? "" : " " + code) + " to a token exchange"));
    }

    private Mono<Issued> success(String provider, Map<String, Object> body) {
        Object token = body.get("access_token");
        Object type = body.get("token_type");
        if (!(token instanceof String access) || access.isBlank()) {
            return Mono.error(malformed(provider, "lacks access_token"));
        }
        if (!(type instanceof String t) || !t.trim().toLowerCase(Locale.ROOT).equals("bearer")) {
            return Mono.error(malformed(provider, "token_type is not Bearer"));
        }
        return Mono.just(new Issued(access, expiresIn(body.get("expires_in"))));
    }

    private static ExchangeUnavailable malformed(String provider, String what) {
        log.warn("token exchange at provider {}: the answer {}", provider, what);
        return new ExchangeUnavailable("the token exchange answer " + what);
    }

    private static Duration expiresIn(Object value) {
        long seconds = -1;
        if (value instanceof Number n) {
            seconds = n.longValue();
        } else if (value instanceof String s) {
            try {
                seconds = Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // falls back to the default lifetime
            }
        }
        return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_LIFETIME;
    }

    /** The issuer's {@code error} when it is a plain code, so nothing else it sent reaches a message. */
    private static String errorCode(Map<String, Object> body) {
        return body.get("error") instanceof String s && ERROR_CODE.matcher(s).matches() ? s : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
