package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@DisplayName("RequestIdFilter")
class RequestIdFilterTest {

    private final GatewayProperties properties = new GatewayProperties();

    private static ServerWebExchange get(String... headers) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/v1/items");
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return MockServerWebExchange.from(request.build());
    }

    /** Runs the filter and hands back what the chain saw in the Reactor context and MDC. */
    private record Seen(String contextId, String mdcId) {}

    private Seen run(ServerWebExchange exchange) {
        AtomicReference<String> fromContext = new AtomicReference<>();
        AtomicReference<String> fromMdc = new AtomicReference<>();
        // The chain stands in for a handler that logs: it reads the Reactor context, and reads MDC
        // from inside a signal, which is the only place a logging framework would ever see it.
        WebFilterChain chain = e -> Mono.deferContextual(ctx -> {
                    fromContext.set(ctx.getOrDefault(RequestIdFilter.REQUEST_ID_KEY, null));
                    return Mono.<Void>empty();
                })
                .doOnEach(signal -> {
                    String current = MDC.get(RequestIdFilter.REQUEST_ID_KEY);
                    if (current != null) fromMdc.compareAndSet(null, current);
                });
        new RequestIdFilter(properties).filter(exchange, chain).block();
        return new Seen(fromContext.get(), fromMdc.get());
    }

    @Test
    @DisplayName(
            "GIVEN no inbound id WHEN a request arrives SHOULD generate one and put it in the context, the response and the exchange")
    void given_noInboundId_when_filtering_should_generateOne() {
        ServerWebExchange exchange = get();

        Seen seen = run(exchange);

        assertThat(seen.contextId()).isNotBlank();
        assertThat(UUID.fromString(seen.contextId())).isNotNull();
        // The MDC value is the point of the whole filter: a log pattern reads it from there.
        assertThat(seen.mdcId()).isEqualTo(seen.contextId());
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isEqualTo(seen.contextId());
        assertThat((String) exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTR))
                .isEqualTo(seen.contextId());
    }

    @Test
    @DisplayName(
            "GIVEN the caller sent an id WHEN it is trusted SHOULD keep it, so a trace that began upstream stays one trace")
    void given_inboundId_when_trusted_should_keepIt() {
        Seen seen = run(get("X-Request-Id", "abc-123"));

        assertThat(seen.contextId()).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("GIVEN trust-inbound is off WHEN the caller sends an id SHOULD ignore it and generate a fresh one")
    void given_trustInboundOff_when_inboundId_should_generateInstead() {
        properties.getRequestId().setTrustInbound(false);

        Seen seen = run(get("X-Request-Id", "caller-chosen"));

        assertThat(seen.contextId()).isNotEqualTo("caller-chosen");
        assertThat(UUID.fromString(seen.contextId())).isNotNull();
    }

    @Test
    @DisplayName(
            "GIVEN an inbound id with control characters WHEN trusted SHOULD scrub them, so it cannot forge log lines")
    void given_inboundIdWithControlChars_when_trusted_should_scrubIt() {
        Seen seen = run(get("X-Request-Id", "aa\tbb"));

        assertThat(seen.contextId()).isEqualTo("aa_bb");
    }

    @Test
    @DisplayName("GIVEN an absurdly long inbound id WHEN trusted SHOULD cap it rather than let it fill the logs")
    void given_veryLongInboundId_when_trusted_should_capIt() {
        Seen seen = run(get("X-Request-Id", "x".repeat(500)));

        assertThat(seen.contextId()).hasSize(128);
    }

    @Test
    @DisplayName("GIVEN include-in-response is off WHEN responding SHOULD keep the id internal")
    void given_echoOff_when_responding_should_notSendHeader() {
        properties.getRequestId().setIncludeInResponse(false);
        ServerWebExchange exchange = get();

        run(exchange);

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id")).isNull();
    }

    @Test
    @DisplayName("GIVEN a custom header name WHEN a request arrives SHOULD read and echo that one")
    void given_customHeaderName_when_filtering_should_useIt() {
        properties.getRequestId().setHeader("X-Correlation-Id");
        ServerWebExchange exchange = get("X-Correlation-Id", "corr-7");

        Seen seen = run(exchange);

        assertThat(seen.contextId()).isEqualTo("corr-7");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo("corr-7");
    }

    @Test
    @DisplayName(
            "GIVEN the request finished WHEN the thread is reused SHOULD have cleared MDC, so no id leaks onto the next request")
    void given_requestFinished_when_threadReused_should_clearMdc() {
        run(get("X-Request-Id", "leaky"));

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_KEY))
                .as("MDC must not survive the request that set it")
                .isNull();
    }

    @Test
    @DisplayName("GIVEN the chain fails WHEN the error propagates SHOULD still clear MDC")
    void given_chainFails_when_erroring_should_clearMdc() {
        WebFilterChain failing = e -> Mono.error(new IllegalStateException("boom"));

        try {
            new RequestIdFilter(properties).filter(get(), failing).block();
        } catch (IllegalStateException expected) {
            // the point is what happens to MDC, not the exception
        }

        assertThat(MDC.get(RequestIdFilter.REQUEST_ID_KEY)).isNull();
    }
}
