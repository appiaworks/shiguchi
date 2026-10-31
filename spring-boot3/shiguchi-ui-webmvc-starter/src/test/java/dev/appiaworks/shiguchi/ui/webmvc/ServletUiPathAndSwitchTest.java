package dev.appiaworks.shiguchi.ui.webmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.appiaworks.shiguchi.ui.servlet.GrpcUiServletHandler;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;

/**
 * BDD tests for the two ways the servlet UI is configured away from its defaults: a custom {@code
 * shiguchi.ui.path} (the SPA, the API and the redirect all move), a servlet context path (the
 * config's links carry it) and {@code shiguchi.ui.enabled} left unset (no UI routes at all).
 */
@DisplayName("gRPC UI in a servlet application: path and enablement")
class ServletUiPathAndSwitchTest {

    @Nested
    @SpringBootTest(
            classes = ServletUiTestApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"shiguchi.ui.enabled=true", "shiguchi.ui.path=/tools/grpc"})
    @DisplayName("with shiguchi.ui.path=/tools/grpc")
    class CustomPath {

        @Value("${local.server.port}")
        private int port;

        @Test
        @DisplayName("GIVEN a custom UI path WHEN requesting the API there SHOULD answer, and not at the default path")
        void given_customPath_when_requestingApi_should_answerThere() throws Exception {
            var http = new UiHttp(port);

            assertThat(http.get("/tools/grpc/api/schema").statusCode()).isEqualTo(200);
            assertThat(http.get("/grpc-ui/api/schema").statusCode()).isEqualTo(404);
        }

        @Test
        @DisplayName("GIVEN a custom UI path WHEN requesting it SHOULD redirect to the index page under that path")
        void given_customPath_when_requesting_should_redirectUnderThatPath() throws Exception {
            HttpResponse<String> response = new UiHttp(port).get("/tools/grpc");

            assertThat(response.statusCode()).isEqualTo(307);
            assertThat(response.headers().firstValue("Location").orElse("")).endsWith("/tools/grpc/index.html");
        }

        @Test
        @DisplayName("GIVEN a custom UI path WHEN requesting the index page SHOULD serve the bundled frontend there")
        void given_customPath_when_requestingIndex_should_serveFrontendThere() throws Exception {
            assumeTrue(
                    new ClassPathResource("static/grpc-ui/index.html").exists(),
                    "the frontend bundle is built by the shiguchi-ui processResources task");

            HttpResponse<String> response = new UiHttp(port).get("/tools/grpc/index.html");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/html");
        }
    }

    @Nested
    @SpringBootTest(
            classes = ServletUiTestApp.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"shiguchi.ui.enabled=true", "server.servlet.context-path=/app"})
    @DisplayName("with server.servlet.context-path=/app")
    class ContextPath {

        @Value("${local.server.port}")
        private int port;

        @Test
        @DisplayName("GIVEN a context path WHEN requesting the config SHOULD answer links under the context path")
        void given_contextPath_when_requestingConfig_should_answerLinksUnderIt() throws Exception {
            HttpResponse<String> response = new UiHttp(port).get("/app/grpc-ui/api/config");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"swaggerPath\":\"/app/swagger-ui.html\"")
                    .contains("\"pushPath\":\"/app/push\"")
                    .contains("\"pushAdminPath\":\"/app/actuator/gateway-push\"");
        }
    }

    @Nested
    @SpringBootTest(classes = ServletUiTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @DisplayName("with shiguchi.ui.enabled unset")
    class Disabled {

        @Value("${local.server.port}")
        private int port;

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("GIVEN the UI is not enabled WHEN the context starts SHOULD register no UI beans")
        void given_uiNotEnabled_when_contextStarts_should_registerNoUiBeans() {
            assertThat(context.getBeanNamesForType(GrpcUiServletHandler.class)).isEmpty();
        }

        @Test
        @DisplayName("GIVEN the UI is not enabled WHEN requesting its routes SHOULD answer HTTP 404")
        void given_uiNotEnabled_when_requestingRoutes_should_answerNotFound() throws Exception {
            var http = new UiHttp(port);

            assertThat(http.get("/grpc-ui/api/schema").statusCode()).isEqualTo(404);
            assertThat(http.get("/grpc-ui").statusCode()).isEqualTo(404);
        }
    }
}
