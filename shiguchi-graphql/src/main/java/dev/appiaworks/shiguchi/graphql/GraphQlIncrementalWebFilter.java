package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import graphql.incremental.DelayedIncrementalPartialResult;
import graphql.incremental.IncrementalExecutionResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.graphql.server.WebGraphQlHandler;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
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
 * Answers {@code @defer} operations as a {@code multipart/mixed} stream. Neither Spring GraphQL
 * nor DGS renders graphql-java's {@link IncrementalExecutionResult}, which would leave a client
 * with the initial payload only.
 *
 * <p>Only a POST to the GraphQL endpoint whose {@code Accept} lists {@code multipart/mixed}
 * (what Apollo Client and urql send, with {@code deferSpec=20220824}) is handled here. The
 * operation runs through the whole interceptor chain. When it deferred nothing the answer is the
 * usual JSON; otherwise the initial payload goes out at once and each deferred payload follows as
 * its own part (draft 2022-08-24: {@code {data, hasNext}} then
 * {@code {incremental: [{path, label, data}], hasNext}}). Every other request, and a batch, reaches
 * the regular handler with its body intact.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.20.0")
public class GraphQlIncrementalWebFilter implements WebFilter, Ordered {

    static final int MAX_BODY_BYTES = 256 * 1024;
    private static final String BOUNDARY = "-";
    private static final String PART_HEADERS = "Content-Type: application/json; charset=utf-8\r\n\r\n";

    private final WebGraphQlHandler handler;
    private final ObjectMapper mapper;
    private final String path;

    GraphQlIncrementalWebFilter(WebGraphQlHandler handler, ObjectMapper mapper, String path) {
        this.handler = handler;
        this.mapper = mapper;
        this.path = path;
    }

    @Override
    public int getOrder() {
        // before the batch filter, which sits at LOWEST_PRECEDENCE - 50
        return Ordered.LOWEST_PRECEDENCE - 60;
    }

    /** Whether the request asks for a multipart answer. */
    static boolean acceptsMultipart(HttpHeaders headers) {
        for (MediaType accepted : headers.getAccept()) {
            if (accepted.getType().equalsIgnoreCase("multipart")
                    && accepted.getSubtype().equalsIgnoreCase("mixed")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        MediaType contentType = request.getHeaders().getContentType();
        if (request.getMethod() != HttpMethod.POST
                || !path.equals(request.getPath().pathWithinApplication().value())
                || !acceptsMultipart(request.getHeaders())
                || contentType == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return chain.filter(exchange);
        }
        return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
                .map(GraphQlIncrementalWebFilter::bytes)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> dispatch(exchange, chain, body))
                .onErrorResume(DataBufferLimitException.class, e -> tooLarge(exchange));
    }

    private Mono<Void> dispatch(ServerWebExchange exchange, WebFilterChain chain, byte[] body) {
        Map<String, Object> operation = operation(body);
        if (operation == null) {
            return chain.filter(
                    exchange.mutate().request(replay(exchange, body)).build());
        }
        ServerHttpRequest http = exchange.getRequest();
        WebGraphQlRequest graphQlRequest = new WebGraphQlRequest(
                http.getURI(),
                http.getHeaders(),
                http.getCookies(),
                http.getRemoteAddress(),
                exchange.getAttributes(),
                operation,
                http.getId(),
                exchange.getLocaleContext().getLocale());
        return handler.handleRequest(graphQlRequest)
                .flatMap(response -> write(exchange, response))
                // the exchange rides the Reactor context for the interceptors that read it (DGS does)
                .contextWrite(ctx -> ctx.put(ServerWebExchangeContextFilter.EXCHANGE_CONTEXT_ATTRIBUTE, exchange));
    }

    /** The operation object, or null for anything that is not one (empty body, batch, invalid JSON). */
    private Map<String, Object> operation(byte[] body) {
        if (firstNonSpace(body) != '{') return null;
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> operation = mapper.convertValue(root, Map.class);
            return operation;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Mono<Void> write(ServerWebExchange exchange, WebGraphQlResponse graphQlResponse) {
        var response = exchange.getResponse();
        response.getHeaders().putAll(graphQlResponse.getResponseHeaders());
        if (!(graphQlResponse.getExecutionResult() instanceof IncrementalExecutionResult incremental)
                || !incremental.hasNext()) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response.writeWith(
                    Mono.just(response.bufferFactory().wrap(mapper.writeValueAsBytes(graphQlResponse.toMap()))));
        }
        response.getHeaders()
                .setContentType(
                        MediaType.parseMediaType("multipart/mixed; boundary=\"" + BOUNDARY + "\"; deferSpec=20220824"));
        Flux<byte[]> deferred = Flux.from(incremental.getIncrementalItemPublisher())
                .map(DelayedIncrementalPartialResult::toSpecification)
                .map(this::part);
        Flux<byte[]> parts = Flux.concat(Mono.just(part(graphQlResponse.toMap())), deferred, Mono.just(closing()));
        // flush each part as it is produced: the point of @defer is that the client sees it early
        return response.writeAndFlushWith(
                parts.map(bytes -> Mono.just(response.bufferFactory().wrap(bytes))));
    }

    private byte[] part(Map<String, Object> payload) {
        return ("\r\n--" + BOUNDARY + "\r\n" + PART_HEADERS + mapper.writeValueAsString(payload) + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] closing() {
        return ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private Mono<Void> tooLarge(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.CONTENT_TOO_LARGE);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] payload =
                mapper.writeValueAsBytes(Map.of("errors", List.of(Map.of("message", "request body too large"))));
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
