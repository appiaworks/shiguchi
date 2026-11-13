package dev.appiaworks.shiguchi.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The OAuth2 callback page is rendered under {@code script-src 'self'}, so it must carry no inline
 * script, and the script it references must be served next to it, whatever the console path is.
 */
@DisplayName("gRPC console OAuth2 callback page")
class GrpcUiCallbackPageTest {

    private WebTestClient clientAt(String uiPath) {
        var props = new GatewayProperties();
        props.getUi().setPath(uiPath);
        var router = new GrpcUiConfig().grpcUiRouterFunction(props, mock(GrpcUiController.class));
        return WebTestClient.bindToRouterFunction(router).build();
    }

    @Test
    @DisplayName(
            "GIVEN the default path WHEN the callback page is fetched SHOULD have an external script and no inline one")
    void given_defaultPath_when_fetched_should_haveNoInlineScript() {
        String html = clientAt("/grpc-ui")
                .get()
                .uri("/grpc-ui/oauth2-callback.html")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(html).contains("<script src=\"oauth2-callback.js\"></script>");
        assertThat(html.replace("<script src=\"oauth2-callback.js\"></script>", ""))
                .doesNotContain("<script");
    }

    @Test
    @DisplayName("GIVEN a custom path WHEN the callback script is fetched SHOULD be served beside the page with 200")
    void given_customPath_when_scriptFetched_should_serveIt() {
        String js = clientAt("/tools/grpc")
                .get()
                .uri("/tools/grpc/oauth2-callback.js")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(js).contains("window.opener.postMessage").contains("window.location.origin");
    }
}
