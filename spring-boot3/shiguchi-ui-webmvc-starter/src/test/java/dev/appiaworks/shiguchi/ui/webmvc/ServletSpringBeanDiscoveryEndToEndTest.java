package dev.appiaworks.shiguchi.ui.webmvc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.proto.cursus.PushHooksGrpc;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * End-to-end check of spring-bean auto-discovery in a real servlet application: a STATIC service
 * whose only source is a BindableService bean gets its routes with no manual registration.
 *
 * <p>The bean and its class carry no stereotype annotation: the UI starter component-scans
 * {@code dev.appiaworks.shiguchi}, and these must only exist in this test's context.
 */
@SpringBootTest(
        classes = {ServletUiTestApp.class, ServletSpringBeanDiscoveryEndToEndTest.Beans.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "shiguchi.ui.enabled=true",
            "shiguchi.services[0].name=self",
            "shiguchi.services[0].host=localhost",
            "shiguchi.services[0].port=1",
            "shiguchi.services[0].mode=STATIC",
            "shiguchi.services[0].auto-discover.spring-beans=true",
        })
@DisplayName("Spring-bean discovery in a servlet application")
class ServletSpringBeanDiscoveryEndToEndTest {

    private static final String SERVICE = "shiguchi.cursus.v1.PushHooks";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private GrpcRouteRegistry registry;

    @Test
    @DisplayName(
            "GIVEN a STATIC service discovered from a generated bean WHEN the application starts SHOULD load its routes without manual registration")
    void given_discoveredStaticService_when_applicationStarts_should_loadRoutesWithoutManualRegistration() {
        var context = registry.contextByServiceName("self");

        assertThat(context).isPresent();
        assertThat(context.get().getRoutes()).isNotEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a STATIC service discovered from a generated bean WHEN requesting the schema SHOULD list the generated service")
    void given_discoveredStaticService_when_requestingSchema_should_listGeneratedService() throws Exception {
        HttpResponse<String> response = new UiHttp(port).get("/grpc-ui/api/schema");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(SERVICE);
    }

    /** Plain configuration holder: registered through {@code classes}, carries no stereotype. */
    static class Beans {

        @Bean
        PushHooksGrpc.PushHooksImplBase pushHooksService() {
            return new HooksService();
        }
    }

    /** Subclass of the generated PushHooks ImplBase, declared outside the generated class. */
    static class HooksService extends PushHooksGrpc.PushHooksImplBase {}
}
