package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.filter.reactive.ServerWebExchangeContextFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs a batched request: a POST to the GraphQL endpoint whose JSON body is an array of
 * operations. Each element goes through the whole interceptor chain on its own (so limits,
 * introspection and the cost budget apply per operation), in order, and the response is the array
 * of results in the same order. Any other request, array or not, reaches the regular handler
 * with its body intact.
 *
 * <p>Each operation gets its own DataLoader registry, so loaders batch within an operation, not
 * across the array.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.20.0")
public class GraphQlBatchWebFilter implements WebFilter, Ordered {

    /** Body bytes allowed per operation of the batch; the whole body may be this times the maximum. */
    static final int BYTES_PER_OPERATION = 256 * 1024;

    private final WebGraphQlHandler handler;
    private final ObjectMapper mapper;
    private final String path;
    private final int maxOperations;

    GraphQlBatchWebFilter(WebGraphQlHandler handler, ObjectMapper mapper, String path, int maxOperations) {
        this.handler = handler;
        this.mapper = mapper;
        this.path = path;
        this.maxOperations = maxOperations;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        MediaType contentType = request.getHeaders().getContentType();
        if (request.getMethod() != HttpMethod.POST
                || !path.equals(request.getPath().pathWithinApplication().value())
                || contentType == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return chain.filter(exchange);
        }
        return DataBufferUtils.join(request.getBody(), BYTES_PER_OPERATION * maxOperations)
                .map(GraphQlBatchWebFilter::bytes)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> dispatch(exchange, chain, body))
                .onErrorResume(
                        DataBufferLimitException.class,
                        e -> error(exchange, HttpStatus.CONTENT_TOO_LARGE, "request body too large"));
    }

    private Mono<Void> dispatch(ServerWebExchange exchange, WebFilterChain chain, byte[] body) {
        if (body.length == 0 || firstNonSpace(body) != '[') {
            return chain.filter(
                    exchange.mutate().request(replay(exchange, body)).build());
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            return error(exchange, HttpStatus.BAD_REQUEST, "invalid JSON body");
        }
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > maxOperations) {
            int size = root == null ? 0 : root.size();
            return error(
                    exchange,
                    HttpStatus.BAD_REQUEST,
                    "batch of " + size + " operations exceeds shiguchi.graphql.batch.max-operations (" + maxOperations
                            + ")");
        }
        List<Map<String, Object>> operations = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            if (!node.isObject()) {
                return error(exchange, HttpStatus.BAD_REQUEST, "every element of a batch must be a JSON object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> operation = mapper.convertValue(node, Map.class);
            operations.add(operation);
        }
        return Flux.range(0, operations.size())
                .concatMap(i -> handler.handleRequest(webRequest(exchange, operations.get(i), i)))
                .collectList()
                .flatMap(responses -> write(exchange, responses))
                // the exchange rides the Reactor context for the interceptors that read it (DGS does)
                .contextWrite(ctx -> ctx.put(ServerWebExchangeContextFilter.EXCHANGE_CONTEXT_ATTRIBUTE, exchange));
    }

    private static WebGraphQlRequest webRequest(ServerWebExchange exchange, Map<String, Object> body, int index) {
        ServerHttpRequest http = exchange.getRequest();
        return new WebGraphQlRequest(
                http.getURI(),
                http.getHeaders(),
                http.getCookies(),
                http.getRemoteAddress(),
                exchange.getAttributes(),
                body,
                http.getId() + "-" + index,
                exchange.getLocaleContext().getLocale());
    }

    private Mono<Void> write(ServerWebExchange exchange, List<WebGraphQlResponse> responses) {
        long retryAfter = 0;
        List<Map<String, Object>> results = new ArrayList<>(responses.size());
        for (WebGraphQlResponse response : responses) {
            results.add(response.toMap());
            String header = response.getResponseHeaders().getFirst("Retry-After");
            if (header != null) {
                try {
                    retryAfter = Math.max(retryAfter, Long.parseLong(header.trim()));
                } catch (NumberFormatException ignored) {
                    // not a delay in seconds: not copied
                }
            }
        }
        if (retryAfter > 0) {
            exchange.getResponse().getHeaders().set("Retry-After", Long.toString(retryAfter));
        }
        return json(exchange, HttpStatus.OK, mapper.writeValueAsBytes(results));
    }

    private Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String message) {
        return json(exchange, status, mapper.writeValueAsBytes(Map.of("errors", List.of(Map.of("message", message)))));
    }

    private static Mono<Void> json(ServerWebExchange exchange, HttpStatus status, byte[] payload) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
    }

    private static ServerHttpRequest replay(ServerWebExchange exchange, byte[] body) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return body.length == 0
                        ? Flux.empty()
                        : Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
    }

    private static byte[] bytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static int firstNonSpace(byte[] body) {
        for (byte b : body) {
            if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return b;
        }
        return -1;
    }
}
