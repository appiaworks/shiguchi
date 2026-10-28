package dev.appiaworks.shiguchi.graphql;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebFilter that logs raw GraphQL HTTP request bodies at TRACE level.
 *
 * Only activates when TRACE is enabled — zero overhead at DEBUG and above.
 * Reads and re-wraps the request body so downstream handlers still see it.
 *
 * Enable:
 *   logging.level.dev.appiaworks.shiguchi.graphql.GraphQlLoggingFilter: TRACE
 */
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GraphQlLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(GraphQlLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!log.isTraceEnabled() || !exchange.getRequest().getPath().value().startsWith("/graphql")) {
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(joined -> {
                    byte[] bytes = new byte[joined.readableByteCount()];
                    joined.read(bytes);
                    DataBufferUtils.release(joined);

                    log.trace(
                            "[GraphQL HTTP→] {} {} body={}",
                            exchange.getRequest().getMethod(),
                            exchange.getRequest().getPath(),
                            new String(bytes, StandardCharsets.UTF_8));

                    DataBuffer copy = exchange.getResponse().bufferFactory().wrap(bytes);
                    ServerHttpRequestDecorator mutated = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(copy);
                        }
                    };
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }
}
