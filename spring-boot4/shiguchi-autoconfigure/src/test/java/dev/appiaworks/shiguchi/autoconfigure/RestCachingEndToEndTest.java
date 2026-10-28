package dev.appiaworks.shiguchi.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.AnnotationsProto;
import com.google.api.HttpProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * HTTP caching through the whole Boot 4 gateway against a real gRPC backend that counts its
 * calls: {@code services[].cache} rules and their {@code Cache-Control}, the response cache (hits,
 * per-caller keys, public sharing, eviction on a write), request coalescing, AIP-154 resource
 * etags ({@code If-None-Match} → 304, {@code If-Match} → the request's {@code etag}, a stale one →
 * 412) and gateway-side gzip with a representation-specific tag.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=false",
            "shiguchi.graphql.enabled=false",
            "shiguchi.openapi.enabled=false",
            "shiguchi.rest.response-cache.enabled=true",
            "shiguchi.rest.resource-etags=true",
            "shiguchi.rest.compression.enabled=true",
            "shiguchi.rest.compression.min-size=1KB",
            "management.endpoints.web.exposure.include=gateway-cache"
        })
@DisplayName("HTTP caching, response cache, resource etags and compression end to end (Boot 4)")
class RestCachingEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor ITEM = FILE.findMessageTypeByName("Item");
    private static final Descriptor GET = FILE.findMessageTypeByName("GetItem");
    private static final Descriptor UPDATE = FILE.findMessageTypeByName("UpdateItem");
    private static final Descriptor DELETE = FILE.findMessageTypeByName("DeleteItem");

    /** Calls the backend received, per method. */
    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

    /** Version per item id; the etag is {@code v<version>}. */
    private static final Map<String, AtomicInteger> VERSIONS = new ConcurrentHashMap<>();

    private static final Server BACKEND = startBackend();
    private static final Path PB_FILE = writeDescriptorSet();

    @DynamicPropertySource
    static void backend(DynamicPropertyRegistry registry) {
        registry.add("shiguchi.services[0].name", () -> "items");
        registry.add("shiguchi.services[0].mode", () -> "PB_FILE");
        registry.add("shiguchi.services[0].pb-descriptor-path", () -> PB_FILE.toString());
        registry.add("shiguchi.services[0].host", () -> "localhost");
        registry.add("shiguchi.services[0].port", () -> BACKEND.getPort());
        registry.add("shiguchi.services[0].cache[0].methods", () -> "Get,Big");
        registry.add("shiguchi.services[0].cache[0].ttl", () -> "60s");
        registry.add("shiguchi.services[0].cache[1].methods", () -> "Shared");
        registry.add("shiguchi.services[0].cache[1].ttl", () -> "60s");
        registry.add("shiguchi.services[0].cache[1].visibility", () -> "PUBLIC");
        registry.add("shiguchi.services[0].cache[1].shared-max-age", () -> "120s");
        registry.add("shiguchi.services[0].cache[1].stale-while-revalidate", () -> "30s");
        // Slow is cacheable, but kept out of the gateway's cache: only coalescing can save its calls.
        registry.add("shiguchi.services[0].cache[2].methods", () -> "Slow");
        registry.add("shiguchi.services[0].cache[2].ttl", () -> "60s");
        registry.add("shiguchi.services[0].cache[2].server-cache", () -> "false");
    }

    @AfterAll
    static void stop() throws IOException {
        BACKEND.shutdownNow();
        Files.deleteIfExists(PB_FILE);
    }

    private WebTestClient client;
    private String baseUrl;

    @Autowired
    private Environment environment;

    @BeforeEach
    void bindClient() {
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port");
        client = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl(baseUrl)
                .build();
    }

    private static int calls(String method) {
        return CALLS.computeIfAbsent(method, m -> new AtomicInteger()).get();
    }

    @Test
    @DisplayName(
            "GIVEN a cache rule and the response cache WHEN the same GET is sent twice SHOULD call the backend once and mark the second a hit")
    void given_cacheRule_when_sameGetTwice_should_callBackendOnce() {
        int before = calls("Get");

        client.get()
                .uri("/v1/items/a1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "max-age=60")
                .expectHeader()
                .valueEquals("Cache-Status", "shiguchi; fwd=miss; stored");
        client.get()
                .uri("/v1/items/a1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "shiguchi; hit")
                .expectHeader()
                .exists(HttpHeaders.AGE)
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo("a1");

        assertThat(calls("Get") - before).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN an AUTO rule WHEN two callers send different Authorization headers SHOULD answer each privately from its own backend call")
    void given_autoRule_when_differentCallers_should_keepSeparateEntries() {
        int before = calls("Get");

        for (String token : List.of("Bearer alice", "Bearer bob", "Bearer alice")) {
            client.get()
                    .uri("/v1/items/p1")
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.CACHE_CONTROL, "private, max-age=60");
        }

        // alice twice, bob once: alice's second request is her own cached answer, never bob's.
        assertThat(calls("Get") - before).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN a PUBLIC rule WHEN different callers ask SHOULD share one entry and send the public directives")
    void given_publicRule_when_differentCallers_should_shareEntry() {
        int before = calls("Shared");

        client.get()
                .uri("/v1/shared/s1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer alice")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "public, max-age=60, s-maxage=120, stale-while-revalidate=30");
        client.get()
                .uri("/v1/shared/s1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer bob")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "shiguchi; hit");

        assertThat(calls("Shared") - before).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a cacheable route WHEN answered SHOULD vary on X-Fields, and on Accept-Encoding only when the body is large enough to compress")
    void given_cacheableRoute_when_answered_should_varyOnRepresentationHeaders() {
        List<String> small = client.get()
                .uri("/v1/items/v1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getVary();
        List<String> large = client.get()
                .uri("/v1/big/v1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getVary();

        assertThat(small).containsExactly("X-Fields");
        assertThat(large).containsExactly("X-Fields", "Accept-Encoding");
    }

    @Test
    @DisplayName(
            "GIVEN resource etags WHEN a GET answers SHOULD send the resource's etag field as ETag and answer its If-None-Match with 304")
    void given_resourceEtags_when_getAnswers_should_sendFieldAsEtagAnd304() {
        String etag = client.get()
                .uri("/v1/items/e1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseHeaders()
                .getETag();

        assertThat(etag).isEqualTo("\"v1\"");

        client.get()
                .uri("/v1/items/e1")
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectHeader()
                .valueEquals(HttpHeaders.ETAG, "\"v1\"")
                .expectBody()
                .isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN resource etags WHEN a PATCH sends a stale If-Match SHOULD answer 412 and leave the resource alone")
    void given_staleIfMatch_when_patched_should_answer412() {
        client.get().uri("/v1/items/u1").exchange().expectStatus().isOk();

        client.patch()
                .uri("/v1/items/u1")
                .header(HttpHeaders.IF_MATCH, "\"v0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"changed\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(412);

        assertThat(VERSIONS.get("u1").get()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "GIVEN a cached GET WHEN a PATCH with the current If-Match succeeds SHOULD bump the version and evict the cached read")
    void given_currentIfMatch_when_patched_should_updateAndEvictCachedRead() {
        client.get()
                .uri("/v1/items/w1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ETAG, "\"v1\"");

        client.patch()
                .uri("/v1/items/w1")
                .header(HttpHeaders.IF_MATCH, "\"v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"changed\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ETAG, "\"v2\"");

        // The cached v1 read is gone: the next GET goes to the backend and sees v2.
        client.get()
                .uri("/v1/items/w1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ETAG, "\"v2\"")
                .expectHeader()
                .valueEquals("Cache-Status", "shiguchi; fwd=miss; stored");
    }

    @Test
    @DisplayName(
            "GIVEN resource etags WHEN a DELETE's If-Match is stale and the backend answers FAILED_PRECONDITION SHOULD answer 412")
    void given_staleIfMatch_when_deleted_should_answer412() {
        client.get().uri("/v1/items/d1").exchange().expectStatus().isOk();

        client.delete()
                .uri("/v1/items/d1")
                .header(HttpHeaders.IF_MATCH, "\"v9\"")
                .exchange()
                .expectStatus()
                .isEqualTo(412);
        client.delete()
                .uri("/v1/items/d1")
                .header(HttpHeaders.IF_MATCH, "\"v1\"")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("GIVEN resource etags WHEN If-Match names two tags SHOULD answer 400 without calling the backend")
    void given_twoIfMatchTags_when_patched_should_answer400() {
        int before = calls("Update");

        client.patch()
                .uri("/v1/items/m1")
                .header(HttpHeaders.IF_MATCH, "\"v1\", \"v2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"x\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();

        assertThat(calls("Update")).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "GIVEN a route kept out of the response cache WHEN many identical GETs arrive at once SHOULD still reach the backend per request without coalescing on")
    void given_noServerCache_when_concurrentGets_should_notCoalesceByDefault() {
        int before = calls("Slow");
        WebClient web = WebClient.create(baseUrl);

        List<Integer> statuses = Flux.range(0, 4)
                .flatMap(i -> web.get()
                        .uri("/v1/slow/c1")
                        .exchangeToMono(
                                r -> r.releaseBody().thenReturn(r.statusCode().value())))
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(statuses).containsOnly(200);
        assertThat(calls("Slow") - before).isEqualTo(4);
    }

    @Test
    @DisplayName(
            "GIVEN compression WHEN a large body is requested with Accept-Encoding gzip SHOULD send it gzipped with a representation-specific strong ETag")
    void given_compression_when_largeBodyAcceptsGzip_should_gzipAndTagTheEncoding() throws IOException {
        // Spring's default connector asks for compression itself and inflates the body, dropping
        // Content-Encoding; a plain Reactor Netty client shows what the gateway actually sent.
        WebTestClient raw = WebTestClient.bindToServer(
                        new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                                reactor.netty.http.client.HttpClient.create()))
                .responseTimeout(Duration.ofSeconds(30))
                .baseUrl(baseUrl)
                .build();
        EntityExchangeResult<byte[]> identity = raw.get()
                .uri("/v1/big/b1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();
        EntityExchangeResult<byte[]> gzipped = raw.get()
                .uri("/v1/big/b1")
                .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.CONTENT_ENCODING, "gzip")
                .expectBody()
                .returnResult();

        String identityTag = identity.getResponseHeaders().getETag();
        String gzipTag = gzipped.getResponseHeaders().getETag();
        assertThat(identity.getResponseHeaders().getFirst(HttpHeaders.CONTENT_ENCODING))
                .isNull();
        assertThat(gzipTag).isEqualTo(identityTag.substring(0, identityTag.length() - 1) + "-gzip\"");
        byte[] inflated = new GZIPInputStream(new ByteArrayInputStream(gzipped.getResponseBody())).readAllBytes();
        assertThat(inflated).isEqualTo(identity.getResponseBody());
    }

    @Test
    @DisplayName("GIVEN the gateway-cache endpoint WHEN read and deleted SHOULD report counts and empty the cache")
    void given_cacheEndpoint_when_readAndDeleted_should_reportAndClear() {
        client.get().uri("/v1/items/x1").exchange().expectStatus().isOk();

        client.get()
                .uri("/actuator/gateway-cache")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true)
                .jsonPath("$.entries")
                .value(n -> assertThat(((Number) n).longValue()).isPositive());
        client.delete().uri("/actuator/gateway-cache").exchange().expectStatus().isOk();

        client.get()
                .uri("/v1/items/x1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Status", "shiguchi; fwd=miss; stored");
    }

    // ---------------------------------------------------------------- the gRPC backend

    /**
     * lib.Items: Get (GET /v1/items/{id}), Shared (GET /v1/shared/{id}), Big (GET /v1/big/{id}, a
     * 5 KB name), Slow (GET /v1/slow/{id}, answers after 300 ms), Update (PATCH
     * /v1/items/{item.id}, body "item", ABORTED on a stale etag) and Delete (DELETE
     * /v1/items/{id}, FAILED_PRECONDITION on a stale etag). Items carry {@code etag = v<version>}.
     */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("lib/items.proto")
                    .setSyntax("proto3")
                    .setPackage("lib")
                    .addDependency("google/api/annotations.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(string("id", 1))
                            .addField(string("name", 2))
                            .addField(string("etag", 3)))
                    .addMessageType(
                            DescriptorProto.newBuilder().setName("GetItem").addField(string("id", 1)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("UpdateItem")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("item")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                                    .setTypeName(".lib.Item")
                                    .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("DeleteItem")
                            .addField(string("id", 1))
                            .addField(string("etag", 2)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Items")
                            .addMethod(method(
                                    "Get", "GetItem", HttpRule.newBuilder().setGet("/v1/items/{id}")))
                            .addMethod(method(
                                    "Shared", "GetItem", HttpRule.newBuilder().setGet("/v1/shared/{id}")))
                            .addMethod(method(
                                    "Big", "GetItem", HttpRule.newBuilder().setGet("/v1/big/{id}")))
                            .addMethod(method(
                                    "Slow", "GetItem", HttpRule.newBuilder().setGet("/v1/slow/{id}")))
                            .addMethod(method(
                                    "Update",
                                    "UpdateItem",
                                    HttpRule.newBuilder()
                                            .setPatch("/v1/items/{item.id}")
                                            .setBody("item")))
                            .addMethod(method(
                                    "Delete",
                                    "DeleteItem",
                                    HttpRule.newBuilder().setDelete("/v1/items/{id}"))))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {AnnotationsProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder string(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static MethodDescriptorProto.Builder method(String name, String input, HttpRule.Builder rule) {
        return MethodDescriptorProto.newBuilder()
                .setName(name)
                .setInputType(".lib." + input)
                .setOutputType(".lib.Item")
                .setOptions(MethodOptions.newBuilder().setExtension(AnnotationsProto.http, rule.build()));
    }

    private static Path writeDescriptorSet() {
        try {
            Path path = Files.createTempFile("lib-items", ".pb");
            FileDescriptorSet set = FileDescriptorSet.newBuilder()
                    .addFile(DescriptorProtos.getDescriptor().toProto())
                    .addFile(HttpProto.getDescriptor().toProto())
                    .addFile(AnnotationsProto.getDescriptor().toProto())
                    .addFile(FILE.toProto())
                    .build();
            Files.write(path, set.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> descriptor(String name, Descriptor input) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("lib.Items/" + name)
                .setRequestMarshaller(DynamicMessageMarshaller.forDescriptor(input))
                .setResponseMarshaller(DynamicMessageMarshaller.forDescriptor(ITEM))
                .build();
    }

    private static String str(DynamicMessage message, String field) {
        return (String) message.getField(message.getDescriptorForType().findFieldByName(field));
    }

    private static DynamicMessage item(String id, String name) {
        int version = VERSIONS.computeIfAbsent(id, k -> new AtomicInteger(1)).get();
        return DynamicMessage.newBuilder(ITEM)
                .setField(ITEM.findFieldByName("id"), id)
                .setField(ITEM.findFieldByName("name"), name)
                .setField(ITEM.findFieldByName("etag"), "v" + version)
                .build();
    }

    private static ServerCalls.UnaryMethod<DynamicMessage, DynamicMessage> reading(
            String method, java.util.function.Function<String, String> name, long delayMillis) {
        return (request, observer) -> {
            CALLS.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String id = str(request, "id");
            observer.onNext(item(id, name.apply(id)));
            observer.onCompleted();
        };
    }

    private static Server startBackend() {
        ServerServiceDefinition service = ServerServiceDefinition.builder("lib.Items")
                .addMethod(descriptor("Get", GET), ServerCalls.asyncUnaryCall(reading("Get", id -> "item " + id, 0)))
                .addMethod(
                        descriptor("Shared", GET),
                        ServerCalls.asyncUnaryCall(reading("Shared", id -> "shared " + id, 0)))
                .addMethod(
                        descriptor("Big", GET), ServerCalls.asyncUnaryCall(reading("Big", id -> "x".repeat(5000), 0)))
                .addMethod(
                        descriptor("Slow", GET), ServerCalls.asyncUnaryCall(reading("Slow", id -> "slow " + id, 300)))
                .addMethod(descriptor("Update", UPDATE), ServerCalls.asyncUnaryCall((request, observer) -> {
                    CALLS.computeIfAbsent("Update", m -> new AtomicInteger()).incrementAndGet();
                    DynamicMessage changes = (DynamicMessage) request.getField(UPDATE.findFieldByName("item"));
                    String id = str(changes, "id");
                    AtomicInteger version = VERSIONS.computeIfAbsent(id, k -> new AtomicInteger(1));
                    String etag = str(changes, "etag");
                    if (!etag.isEmpty() && !etag.equals("v" + version.get())) {
                        observer.onError(Status.ABORTED
                                .withDescription("etag " + etag + " is not current")
                                .asRuntimeException());
                        return;
                    }
                    version.incrementAndGet();
                    observer.onNext(item(id, str(changes, "name")));
                    observer.onCompleted();
                }))
                .addMethod(descriptor("Delete", DELETE), ServerCalls.asyncUnaryCall((request, observer) -> {
                    String id = str(request, "id");
                    AtomicInteger version = VERSIONS.computeIfAbsent(id, k -> new AtomicInteger(1));
                    String etag = str(request, "etag");
                    if (!etag.isEmpty() && !etag.equals("v" + version.get())) {
                        observer.onError(Status.FAILED_PRECONDITION
                                .withDescription("etag " + etag + " is not current")
                                .asRuntimeException());
                        return;
                    }
                    observer.onNext(item(id, "deleted"));
                    observer.onCompleted();
                }))
                .build();
        try {
            return ServerBuilder.forPort(0).addService(service).build().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
