package dev.appiaworks.shiguchi.ui.webmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.ui.GrpcUiSchemaBuilder;
import io.grpc.Metadata;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.ClassUtils;

/**
 * BDD tests for the embedded gRPC UI in a real servlet (Spring MVC, Tomcat) application started
 * from the webmvc starter, against an in-process gRPC backend. The classpath of this module has no
 * WebFlux, so every request below also proves the servlet adapter works without it.
 */
@SpringBootTest(
        classes = ServletUiTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=true",
            "shiguchi.client.forward-headers=x-tenant-id,x-principal",
        })
@DisplayName("gRPC UI in a servlet application")
class ServletUiEndToEndTest {

    private static EchoBackend backend;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private GrpcRouteRegistry registry;

    @Autowired
    private GrpcUiSchemaBuilder schemaBuilder;

    private UiHttp http;

    @BeforeAll
    static void startBackend() throws Exception {
        backend = EchoBackend.start();
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @BeforeEach
    void register() throws Exception {
        http = new UiHttp(port);
        if (!registry.contextByServiceFqn(EchoBackend.SERVICE).isPresent()) {
            registry.registerContext(backend.context());
            schemaBuilder.rebuild();
        }
        backend.resetMetadata();
    }

    @Test
    @DisplayName("GIVEN the webmvc starter classpath WHEN checking for WebClient SHOULD find no WebFlux client")
    void given_webmvcStarterClasspath_when_checkingForWebClient_should_findNone() {
        assertThat(ClassUtils.isPresent("org.springframework.web.reactive.function.client.WebClient", null))
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a registered service WHEN requesting the schema SHOULD list the service")
    void given_registeredService_when_requestingSchema_should_listIt() throws Exception {
        HttpResponse<String> response = http.get("/grpc-ui/api/schema");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        assertThat(response.body()).contains("test.ui.EchoService").contains("EchoStream");
    }

    @Test
    @DisplayName("GIVEN the UI is enabled WHEN requesting the config SHOULD answer with the UI links")
    void given_uiEnabled_when_requestingConfig_should_answerWithLinks() throws Exception {
        HttpResponse<String> response = http.get("/grpc-ui/api/config");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("swaggerPath").contains("authProviders");
    }

    @Test
    @DisplayName("GIVEN a unary method WHEN invoking it SHOULD return the backend's answer in an OK InvokeResponse")
    void given_unaryMethod_when_invoking_should_returnOkResponse() throws Exception {
        HttpResponse<String> response = http.post(
                "/grpc-ui/api/invoke", UiHttp.invokeJson(EchoBackend.SERVICE, "Echo", "{\"text\":\"hello\"}"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"grpcStatus\":\"OK\"").contains("hello");
    }

    @Test
    @DisplayName("GIVEN the host's CSRF protection WHEN invoking without a token SHOULD reject the call with 403")
    void given_hostCsrfProtection_when_invokingWithoutToken_should_reject() throws Exception {
        HttpResponse<String> response = http.postWithoutCsrf(
                "/grpc-ui/api/invoke", UiHttp.invokeJson(EchoBackend.SERVICE, "Echo", "{\"text\":\"hello\"}"));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("GIVEN an unknown service WHEN invoking it SHOULD answer HTTP 200 with a NOT_FOUND InvokeResponse")
    void given_unknownService_when_invoking_should_returnNotFoundResponse() throws Exception {
        HttpResponse<String> response =
                http.post("/grpc-ui/api/invoke", UiHttp.invokeJson("test.ui.Missing", "Echo", "{}"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"grpcStatus\":\"NOT_FOUND\"").contains("test.ui.Missing");
    }

    @Test
    @DisplayName("GIVEN a client-streaming method WHEN invoking it with several payloads SHOULD send them all")
    void given_clientStreamingMethod_when_invokingWithPayloads_should_sendAll() throws Exception {
        String body = "{\"serviceName\":\"test.ui.EchoService\",\"methodName\":\"EchoCollect\","
                + "\"payload\":\"{\\\"text\\\":\\\"a\\\"}\","
                + "\"payloads\":[\"{\\\"text\\\":\\\"a\\\"}\",\"{\\\"text\\\":\\\"b\\\"}\"]}";

        HttpResponse<String> response = http.post("/grpc-ui/api/invoke", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"grpcStatus\":\"OK\"").contains("a,b");
    }

    @Test
    @DisplayName("GIVEN a server-streaming method WHEN invoking the stream endpoint SHOULD emit data events then done")
    void given_serverStreamingMethod_when_streaming_should_emitDataThenDone() throws Exception {
        HttpResponse<String> response = http.post(
                "/grpc-ui/api/invoke/stream", UiHttp.invokeJson(EchoBackend.SERVICE, "EchoStream", "{\"text\":\"s\"}"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        String body = response.body();
        assertThat(body).contains("event:data").contains("s-0").contains("s-1").contains("s-2");
        assertThat(body).contains("event:done").contains("durationMs");
        assertThat(body.lastIndexOf("event:done")).isGreaterThan(body.lastIndexOf("s-2"));
        // The console JSON.parses each data line: it must be the raw JSON, not a JSON-encoded string.
        assertThat(body).contains("data:{\"text\":\"s-0\"}").contains("data:{\"durationMs\":");
    }

    @Test
    @DisplayName("GIVEN an unknown service WHEN invoking the stream endpoint SHOULD answer HTTP 404")
    void given_unknownService_when_streaming_should_answerNotFound() throws Exception {
        HttpResponse<String> response =
                http.post("/grpc-ui/api/invoke/stream", UiHttp.invokeJson("test.ui.Missing", "EchoStream", "{}"));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("GIVEN the UI path WHEN requesting it SHOULD redirect to its index page")
    void given_uiPath_when_requesting_should_redirectToIndex() throws Exception {
        HttpResponse<String> bare = http.get("/grpc-ui");
        HttpResponse<String> slash = http.get("/grpc-ui/");

        assertThat(bare.statusCode()).isEqualTo(307);
        assertThat(bare.headers().firstValue("Location").orElse("")).endsWith("/grpc-ui/index.html");
        assertThat(slash.statusCode()).isEqualTo(307);
        assertThat(slash.headers().firstValue("Location").orElse("")).endsWith("/grpc-ui/index.html");
    }

    @Test
    @DisplayName("GIVEN the bundled frontend WHEN requesting its index page SHOULD serve the single-page app")
    void given_bundledFrontend_when_requestingIndex_should_serveIt() throws Exception {
        assumeTrue(
                new ClassPathResource("static/grpc-ui/index.html").exists(),
                "the frontend bundle is built by the shiguchi-ui processResources task");

        HttpResponse<String> response = http.get("/grpc-ui/index.html");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
    }

    @Test
    @DisplayName(
            "GIVEN an authenticated caller with a forwarded header WHEN invoking SHOULD reach the backend as metadata")
    void given_authenticatedCaller_when_invoking_should_forwardPrincipalAndHeaders() throws Exception {
        String auth = UiHttp.basic("alice", "secret");

        http.post(
                "/grpc-ui/api/invoke",
                UiHttp.invokeJson(EchoBackend.SERVICE, "Echo", "{\"text\":\"who\"}"),
                "Authorization",
                auth,
                "x-tenant-id",
                "t-42",
                "x-principal",
                "mallory");

        Metadata metadata = backend.lastMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.get(key("x-principal"))).isEqualTo("alice");
        assertThat(metadata.get(key("authorization"))).isEqualTo(auth);
        assertThat(metadata.get(key("x-tenant-id"))).isEqualTo("t-42");
        assertThat(metadata.getAll(key("x-principal"))).containsExactly("alice");
    }

    @Test
    @DisplayName("GIVEN an anonymous caller WHEN streaming SHOULD reach the backend with the anonymous principal")
    void given_anonymousCaller_when_streaming_should_forwardAnonymousPrincipal() throws Exception {
        http.post(
                "/grpc-ui/api/invoke/stream", UiHttp.invokeJson(EchoBackend.SERVICE, "EchoStream", "{\"text\":\"x\"}"));

        Metadata metadata = backend.lastMetadata();
        assertThat(metadata).isNotNull();
        assertThat(metadata.get(key("x-principal"))).isEqualTo("anonymous");
        assertThat(metadata.get(key("authorization"))).isNull();
    }

    @Test
    @DisplayName("GIVEN custom metadata in the invoke request WHEN invoking SHOULD merge it into the call")
    void given_customMetadata_when_invoking_should_mergeIt() throws Exception {
        String body = "{\"serviceName\":\"test.ui.EchoService\",\"methodName\":\"Echo\","
                + "\"payload\":\"{\\\"text\\\":\\\"m\\\"}\","
                + "\"metadata\":[{\"key\":\" X-Custom \",\"value\":\"v1\"}]}";

        http.post("/grpc-ui/api/invoke", body);

        assertThat(backend.lastMetadata().get(key("x-custom"))).isEqualTo("v1");
    }

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}
