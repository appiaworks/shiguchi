package dev.appiaworks.shiguchi.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.OpenApiConfig;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.Metadata;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * BDD tests for the web-stack-neutral {@link GrpcUiService}: what it answers without any HTTP
 * layer in front of it. The invoke and stream paths against a real backend are covered through
 * the WebFlux adapter in {@link GrpcUiControllerTest} and through the servlet adapter in the
 * webmvc starter's end-to-end tests.
 */
@DisplayName("GrpcUiService")
class GrpcUiServiceTest {

    private HttpServer tokenServer;

    @AfterEach
    void stopTokenServer() {
        if (tokenServer != null) tokenServer.stop(0);
    }

    @Test
    @DisplayName("GIVEN configured links WHEN asking for the config SHOULD report the swagger, graphiql and push paths")
    void given_configuredLinks_when_askingForConfig_should_reportPaths() {
        var service = service(new GatewayProperties(), new GrpcRouteRegistry());

        var config = service.config();

        assertThat(config.swaggerPath()).isEqualTo("/swagger-ui.html");
        assertThat(config.graphqlPath()).isEqualTo("/graphiql");
        assertThat(config.pushAdminPath()).isEqualTo("/actuator/gateway-push");
        assertThat(config.authProviders()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN no registered service WHEN asking if one is registered SHOULD say no")
    void given_noRegisteredService_when_askingIfRegistered_should_sayNo() {
        var service = service(new GatewayProperties(), new GrpcRouteRegistry());

        assertThat(service.isRegistered("test.ui.EchoService")).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN an unknown service WHEN invoking SHOULD emit one NOT_FOUND response without touching the metadata")
    void given_unknownService_when_invoking_should_emitNotFoundResponse() {
        var service = service(new GatewayProperties(), new GrpcRouteRegistry());
        var request = new GrpcUiSchemaDto.InvokeRequest("test.ui.Missing", "Echo", "{}", null, null);

        StepVerifier.create(service.invoke(request, reactor.core.publisher.Mono.error(new IllegalStateException())))
                .assertNext(response -> {
                    assertThat(response.grpcStatus()).isEqualTo("NOT_FOUND");
                    assertThat(response.errorMessage()).contains("test.ui.Missing");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN an unknown service WHEN streaming SHOULD fail the flux instead of emitting events")
    void given_unknownService_when_streaming_should_failTheFlux() {
        var service = service(new GatewayProperties(), new GrpcRouteRegistry());
        var request = new GrpcUiSchemaDto.InvokeRequest("test.ui.Missing", "EchoStream", "{}", null, null);

        StepVerifier.create(service.stream(request, reactor.core.publisher.Mono.just(new Metadata())))
                .expectError(IllegalArgumentException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN custom entries WHEN merging them SHOULD add them lowercased and trimmed, blank keys skipped")
    void given_customEntries_when_merging_should_lowercaseTrimAndSkipBlank() {
        var metadata = new Metadata();

        GrpcUiService.mergeCustomMetadata(
                metadata,
                List.of(
                        new GrpcUiSchemaDto.MetadataEntry(" X-One ", "1"),
                        new GrpcUiSchemaDto.MetadataEntry("x-two", null),
                        new GrpcUiSchemaDto.MetadataEntry("  ", "ignored")));

        assertThat(metadata.get(key("x-one"))).isEqualTo("1");
        assertThat(metadata.get(key("x-two"))).isEmpty();
        assertThat(metadata.keys()).containsExactlyInAnyOrder("x-one", "x-two");
    }

    @Test
    @DisplayName("GIVEN a scheme without a client_credentials flow WHEN asking SHOULD say there is none")
    void given_schemeWithoutClientCredentials_when_asking_should_sayNone() {
        var props = new GatewayProperties();
        props.getOpenapi().getSecuritySchemes().put("basic", new OpenApiConfig.SecuritySchemeConfig());
        var service = service(props, new GrpcRouteRegistry());

        assertThat(service.hasClientCredentials("basic")).isFalse();
        assertThat(service.hasClientCredentials("absent")).isFalse();
    }

    @Test
    @DisplayName("GIVEN a token endpoint WHEN exchanging client credentials SHOULD post the form and map the token")
    void given_tokenEndpoint_when_exchanging_should_postFormAndMapToken() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        String url = startTokenServer(
                200, "{\"access_token\":\"tok\",\"expires_in\":60,\"token_type\":\"Bearer\"}", received);
        var service = service(schemeWithTokenUrl(url), new GrpcRouteRegistry());

        StepVerifier.create(service.clientCredentials(
                        "idp", new GrpcUiSchemaDto.ClientCredentialsRequest("cid", "s e&cret", List.of("a", "b"))))
                .assertNext(token -> {
                    assertThat(token.accessToken()).isEqualTo("tok");
                    assertThat(token.expiresIn()).isEqualTo(60L);
                    assertThat(token.tokenType()).isEqualTo("Bearer");
                })
                .verifyComplete();
        assertThat(received.get())
                .contains("grant_type=client_credentials")
                .contains("client_id=cid")
                .contains("client_secret=s+e%26cret")
                .contains("scope=a+b");
    }

    @Test
    @DisplayName("GIVEN a token endpoint answering without expiry WHEN exchanging SHOULD default to one hour")
    void given_tokenWithoutExpiry_when_exchanging_should_defaultToOneHour() throws Exception {
        String url = startTokenServer(200, "{\"access_token\":\"tok\"}", new AtomicReference<>());
        var service = service(schemeWithTokenUrl(url), new GrpcRouteRegistry());

        StepVerifier.create(service.clientCredentials(
                        "idp", new GrpcUiSchemaDto.ClientCredentialsRequest("cid", "secret", null)))
                .assertNext(token -> {
                    assertThat(token.expiresIn()).isEqualTo(3600L);
                    assertThat(token.tokenType()).isEqualTo("Bearer");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a token endpoint rejecting the request WHEN exchanging SHOULD fail with its status and body")
    void given_rejectingTokenEndpoint_when_exchanging_should_failWithStatusAndBody() throws Exception {
        String url = startTokenServer(401, "{\"error\":\"invalid_client\"}", new AtomicReference<>());
        var service = service(schemeWithTokenUrl(url), new GrpcRouteRegistry());

        StepVerifier.create(service.clientCredentials(
                        "idp", new GrpcUiSchemaDto.ClientCredentialsRequest("cid", "bad", null)))
                .expectErrorMatches(
                        ex -> ex.getMessage().contains("401") && ex.getMessage().contains("invalid_client"))
                .verify(Duration.ofSeconds(10));
    }

    // -------------------------------------------------------------------------

    private static GrpcUiService service(GatewayProperties props, GrpcRouteRegistry registry) {
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        var dispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);
        var schemaBuilder = new GrpcUiSchemaBuilder(registry);
        return new GrpcUiService(
                schemaBuilder,
                dispatcher,
                registry,
                props,
                "/swagger-ui.html",
                "/graphiql",
                false,
                "/push",
                "/actuator");
    }

    private static GatewayProperties schemeWithTokenUrl(String tokenUrl) {
        var flow = new OpenApiConfig.SecuritySchemeConfig.OAuthFlowConfig();
        flow.setTokenUrl(tokenUrl);
        var scheme = new OpenApiConfig.SecuritySchemeConfig();
        scheme.setType("oauth2");
        scheme.setClientCredentials(flow);
        var props = new GatewayProperties();
        props.getOpenapi().getSecuritySchemes().put("idp", scheme);
        return props;
    }

    private String startTokenServer(int status, String responseBody, AtomicReference<String> received)
            throws IOException {
        tokenServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        tokenServer.createContext("/token", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        tokenServer.start();
        return "http://localhost:" + tokenServer.getAddress().getPort() + "/token";
    }

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}
