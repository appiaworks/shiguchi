package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import io.grpc.Metadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * BDD-style unit tests for {@link OpenApiBatchLoader#loadMapped(List, Metadata)}.
 *
 * Uses a custom {@link ExchangeFunction} stub to intercept WebClient HTTP calls —
 * no live HTTP server needed, and no Spring version conflicts.
 */
@DisplayName("OpenApiBatchLoader")
class OpenApiBatchLoaderTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    /** Builds a WebClient backed by a fixed response body and status. */
    private WebClient fixedResponseClient(int status, String body) {
        return clientWith(request -> mockResponse(status, body));
    }

    private static Mono<ClientResponse> mockResponse(int status, String body) {
        return Mono.just(ClientResponse.create(HttpStatusCode.valueOf(status))
                .header("Content-Type", "application/json")
                .body(Flux.just(org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(
                        body.getBytes(StandardCharsets.UTF_8))))
                .build());
    }

    /** Builds a WebClient that captures outbound requests and returns a fixed response. */
    private CapturingClient capturingClient(int status, String body) {
        return new CapturingClient(status, body);
    }

    /** Builds a WebClient backed by a custom ExchangeFunction. */
    private WebClient clientWith(ExchangeFunction fn) {
        return WebClient.builder()
                .baseUrl("http://test-svc")
                .exchangeFunction(fn)
                .build();
    }

    private static RestBatchLoaderConfig batchConfig(String endpoint) {
        RestBatchLoaderConfig cfg = new RestBatchLoaderConfig();
        cfg.setType("Product");
        cfg.setEndpoint(endpoint);
        cfg.setIdsParam("ids");
        cfg.setKeyField("id");
        cfg.setBatchEndpoint(true);
        // Generous: a GET that times out is dropped like a failed one, so on a loaded CI runner a
        // 5 s limit turned "one key fails" into "every key fails" (#284's first run).
        cfg.setTimeoutSeconds(30);
        return cfg;
    }

    private static RestBatchLoaderConfig parallelConfig(String endpointTemplate) {
        RestBatchLoaderConfig cfg = new RestBatchLoaderConfig();
        cfg.setType("Product");
        cfg.setEndpoint(endpointTemplate);
        cfg.setKeyField("id");
        cfg.setBatchEndpoint(false);
        // Generous: a GET that times out is dropped like a failed one, so on a loaded CI runner a
        // 5 s limit turned "one key fails" into "every key fails" (#284's first run).
        cfg.setTimeoutSeconds(30);
        return cfg;
    }

    // -------------------------------------------------------------------------
    // Null / empty keys — no HTTP call
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Given null keys, when loadMapped called, should return empty map without invoking exchange function")
    void given_nullKeys_when_loadMapped_should_returnEmptyMapImmediately() {
        List<ClientRequest> captured = new ArrayList<>();
        WebClient client = clientWith(req -> {
            captured.add(req);
            return Mono.error(new AssertionError("should not call"));
        });
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(null, new Metadata()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();

        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName(
            "Given empty keys list, when loadMapped called, should return empty map without invoking exchange function")
    void given_emptyKeysList_when_loadMapped_should_returnEmptyMapImmediately() {
        List<ClientRequest> captured = new ArrayList<>();
        WebClient client = clientWith(req -> {
            captured.add(req);
            return Mono.error(new AssertionError("should not call"));
        });
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of(), new Metadata()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();

        assertThat(captured).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Batch strategy — response parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given batch strategy with bare array response, when loadMapped called with 2 keys, should return map with 2 entities keyed by id")
    void given_batchBareArray_when_loadMappedWith2Keys_should_returnMapWith2Entities() {
        WebClient client = fixedResponseClient(
                200, "[{\"id\":\"10\",\"name\":\"Product A\"},{\"id\":\"20\",\"name\":\"Product B\"}]");
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("10", "20"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).containsOnlyKeys("10", "20");
                    assertThat(result.get("10")).containsEntry("name", "Product A");
                    assertThat(result.get("20")).containsEntry("name", "Product B");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Given batch strategy with wrapped response (resultField set), when loadMapped called, should unwrap array at resultField and return correct map")
    void given_batchWrappedResponse_when_loadMapped_should_unwrapResultFieldAndReturnMap() {
        WebClient client = fixedResponseClient(
                200, "{\"items\":[{\"id\":\"1\",\"title\":\"Item 1\"},{\"id\":\"2\",\"title\":\"Item 2\"}]}");
        RestBatchLoaderConfig cfg = batchConfig("/items");
        cfg.setResultField("items");
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, cfg, objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("1", "2"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).containsOnlyKeys("1", "2");
                    assertThat(result.get("1")).containsEntry("title", "Item 1");
                    assertThat(result.get("2")).containsEntry("title", "Item 2");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "Given batch strategy, when server response omits one requested key, should not include that key in result")
    void given_batchResponse_when_oneKeyAbsent_should_omitThatKeyFromResult() {
        WebClient client = fixedResponseClient(200, "[{\"id\":\"100\",\"value\":\"found\"}]");
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("100", "999"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).containsOnlyKeys("100");
                    assertThat(result).doesNotContainKey("999");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Given batch strategy, when server returns HTTP 500, should return empty map")
    void given_batchStrategy_when_serverReturns500_should_returnEmptyMap() {
        WebClient client = fixedResponseClient(500, "Internal Server Error");
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("1"), new Metadata()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("Given batch strategy, when server returns malformed JSON, should return empty map")
    void given_batchStrategy_when_malformedJson_should_returnEmptyMap() {
        WebClient client = fixedResponseClient(200, "{ not valid json }");
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("1"), new Metadata()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Batch strategy — request construction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given batch strategy with 3 keys, when loadMapped called, should send GET with comma-separated ids in query param")
    void given_batchStrategy_when_3Keys_should_sendGetWithCommaDelimitedIdsParam() {
        CapturingClient cc = capturingClient(200, "[]");
        OpenApiBatchLoader loader = new OpenApiBatchLoader(cc.webClient(), batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("1", "2", "3"), new Metadata()))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();

        assertThat(cc.requests()).hasSize(1);
        URI uri = cc.requests().get(0).url();
        assertThat(uri.getPath()).isEqualTo("/products");
        assertThat(uri.getQuery()).contains("ids=1,2,3");
    }

    // -------------------------------------------------------------------------
    // Parallel strategy
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given parallel strategy with 2 keys, when loadMapped called, should make 2 separate GET requests and merge results")
    void given_parallelStrategy_when_2Keys_should_makeSeparateRequestsAndMergeResults() {
        List<ClientRequest> captured = new CopyOnWriteArrayList<>();
        ExchangeFunction fn = request -> {
            captured.add(request);
            String id = request.url().getPath().replaceAll(".*/", "");
            String respBody = "{\"id\":\"" + id + "\",\"name\":\"Entity-" + id + "\"}";
            return mockResponse(200, respBody);
        };
        WebClient client = clientWith(fn);
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, parallelConfig("/products/{id}"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("A", "B"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).containsOnlyKeys("A", "B");
                    assertThat(result.get("A")).containsEntry("name", "Entity-A");
                    assertThat(result.get("B")).containsEntry("name", "Entity-B");
                })
                .verifyComplete();

        assertThat(captured).hasSize(2);
    }

    @Test
    @DisplayName(
            "Given parallel strategy, when one GET returns 500, should omit that key and return only successful entities")
    void given_parallelStrategy_when_oneRequestFails_should_omitFailedKeyAndReturnRest() {
        ExchangeFunction fn = request -> {
            String path = request.url().getPath();
            if (path.endsWith("/good")) {
                return mockResponse(200, "{\"id\":\"good\",\"name\":\"OK\"}");
            }
            return mockResponse(500, "Error");
        };
        WebClient client = clientWith(fn);
        OpenApiBatchLoader loader = new OpenApiBatchLoader(client, parallelConfig("/products/{id}"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("good", "bad"), new Metadata()))
                .assertNext(result -> {
                    assertThat(result).containsOnlyKeys("good");
                    assertThat(result).doesNotContainKey("bad");
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Authorization header forwarding
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "Given Authorization metadata present, when loadMapped called, should forward Authorization header in outbound request")
    void given_authMetadata_when_loadMapped_should_forwardAuthorizationHeaderInRequest() {
        CapturingClient cc = capturingClient(200, "[{\"id\":\"1\",\"name\":\"Item\"}]");

        Metadata metadata = new Metadata();
        Metadata.Key<String> authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        metadata.put(authKey, "Bearer secret-token");

        OpenApiBatchLoader loader = new OpenApiBatchLoader(cc.webClient(), batchConfig("/products"), objectMapper);

        StepVerifier.create(loader.loadMapped(List.of("1"), metadata))
                .assertNext(result -> assertThat(result).containsKey("1"))
                .verifyComplete();

        assertThat(cc.requests()).hasSize(1);
        assertThat(cc.requests().get(0).headers().get("Authorization")).containsExactly("Bearer secret-token");
    }

    // -------------------------------------------------------------------------
    // Helper: request-capturing WebClient
    // -------------------------------------------------------------------------

    private class CapturingClient {
        private final List<ClientRequest> requests = new CopyOnWriteArrayList<>();
        private final int status;
        private final String body;

        CapturingClient(int status, String body) {
            this.status = status;
            this.body = body;
        }

        WebClient webClient() {
            return WebClient.builder()
                    .baseUrl("http://test-svc")
                    .exchangeFunction(req -> {
                        requests.add(req);
                        return mockResponse(status, body);
                    })
                    .build();
        }

        List<ClientRequest> requests() {
            return requests;
        }
    }
}
