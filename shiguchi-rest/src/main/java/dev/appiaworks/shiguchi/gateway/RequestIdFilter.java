package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RequestIdConfig;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Gives every request an id, and makes it reachable from anywhere that logs
 * ({@code shiguchi.request-id.*}).
 *
 * <p>The id is the caller's {@code X-Request-Id} when they sent one — so a trace that started at
 * an ingress or another service keeps its identity — or a fresh UUID when they did not. It goes
 * three places: the Reactor context, the response headers so the caller can quote it in a bug
 * report, and SLF4J's {@link MDC} so a log pattern can print it without every log statement
 * passing it along.
 *
 * <p>The MDC part is the awkward one on a reactive stack. MDC is thread-local and a reactive
 * request hops threads, so the value is not simply set once: it is written from the Reactor
 * context each time a signal is handled, through {@code contextWrite} plus a per-signal hook,
 * and cleared afterwards so it never leaks onto a pooled thread that goes on to serve someone
 * else. That is why the id lives in the Reactor context as the source of truth, with MDC as a
 * convenience for the logging framework.
 *
 * <p>Ordered before everything else so an id exists for the whole exchange, including anything the
 * rate limiter or the security chain rejects.
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.request-id", name = "enabled", havingValue = "true", matchIfMissing = true)
/**
 * Gives every request an id, and makes it reachable from everywhere it is useful.
 *
 * <p>The caller's {@code X-Request-Id} when they sent one, otherwise a fresh UUID. It goes into
 * the Reactor context, into SLF4J's MDC under {@code requestId} so a log pattern can print it
 * without threading it through every call, and onto the response so the caller can quote it.
 *
 * <p>Runs first ({@link Ordered#HIGHEST_PRECEDENCE}) so everything downstream — the access log
 * included — sees the same id. On an internet-facing gateway set {@code trust-inbound: false} to
 * ignore what the caller sent; a supplied id is trimmed to 128 printable characters either way.
 */
@Order(RequestIdFilter.ORDER)
public class RequestIdFilter implements WebFilter {

    /** First: everything after it, rejections included, should be attributable to a request. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    /** Reactor context key and MDC key holding the current request id. */
    public static final String REQUEST_ID_KEY = "requestId";

    /** Exchange attribute, for code that has the exchange but not the Reactor context. */
    public static final String REQUEST_ID_ATTR = RequestIdFilter.class.getName() + ".requestId";

    private final String headerName;
    private final boolean echoInResponse;
    private final boolean trustInbound;

    public RequestIdFilter(GatewayProperties properties) {
        RequestIdConfig config = properties.getRequestId();
        this.headerName = config.getHeader();
        this.echoInResponse = config.isIncludeInResponse();
        this.trustInbound = config.isTrustInbound();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolve(exchange);
        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);
        if (echoInResponse) {
            exchange.getResponse().getHeaders().set(headerName, requestId);
        }

        // MDC is thread-local and this stack changes threads, so the value is put in place on the
        // thread that is about to run the rest of the chain, and taken out again when that work
        // finishes — otherwise it would leak onto a pooled thread that later serves someone else.
        //
        // Mono.fromRunnable(...).then(...) rather than an operator on the result: an operator
        // added after chain.filter runs *outside* the chain, so it would set MDC only once the
        // handler had already logged. The handler is the reader, so the value has to be there
        // before it runs.
        return Mono.fromRunnable(() -> MDC.put(REQUEST_ID_KEY, requestId))
                .then(Mono.defer(() -> chain.filter(exchange)))
                .contextWrite(Context.of(REQUEST_ID_KEY, requestId))
                .doFinally(ignored -> MDC.remove(REQUEST_ID_KEY));
    }

    /**
     * The caller's id when it is trusted and present, else a new one.
     *
     * <p>{@code trust-inbound: false} matters on an internet-facing gateway: the id reaches logs,
     * so a caller who picks it can make two unrelated requests look like one, or write awkward
     * things into a log line someone later greps.
     */
    private String resolve(ServerWebExchange exchange) {
        if (trustInbound) {
            String inbound = exchange.getRequest().getHeaders().getFirst(headerName);
            if (inbound != null && !inbound.isBlank()) {
                return sanitize(inbound);
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Keeps a caller-supplied id printable and bounded: log-injection characters out, length
     * capped. A caller that sends something stranger gets a truncated, scrubbed version of it
     * rather than a rejected request — the id is for correlation, not authorization.
     */
    private static String sanitize(String value) {
        String trimmed = value.length() > 128 ? value.substring(0, 128) : value;
        StringBuilder clean = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            clean.append(c >= 0x20 && c < 0x7f ? c : '_');
        }
        return clean.toString();
    }
}
