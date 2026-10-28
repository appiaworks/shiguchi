package dev.appiaworks.shiguchi.sample.openfga;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the sample actually replaces the gateway's default authorizer, rather than silently
 * running alongside it — the failure mode that would leave per-object checks unenforced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("wiringtest")
class OpenFgaAuthorizerWiringTest {

    @Autowired
    private GatewayAuthorizer authorizer;

    @Autowired
    private OpenFgaAuthzProperties props;

    @Test
    @DisplayName("GIVEN the sample is enabled WHEN the context starts SHOULD replace the default scope authorizer")
    void given_sampleEnabled_when_contextStarts_should_replaceDefaultAuthorizer() {
        assertThat(authorizer).isInstanceOf(OpenFgaGatewayAuthorizer.class);
        assertThat(authorizer).isNotInstanceOf(ScopeGatewayAuthorizer.class);
    }

    @Test
    @DisplayName("GIVEN route mapping in config WHEN bound SHOULD expose object type, relation and id argument")
    void given_routeMapping_when_bound_should_exposePermission() {
        RouteObjectPermission permission = props.getRoutes().get("GET /v1/orders/{orderId}");
        assertThat(permission).isNotNull();
        assertThat(permission.objectType()).isEqualTo("order");
        assertThat(permission.relation()).isEqualTo("viewer");
        assertThat(permission.idArgument()).isEqualTo("orderId");
        assertThat(permission.objectRef("42")).isEqualTo("order:42");
    }

    @Test
    @DisplayName("GIVEN no explicit cache config WHEN bound SHOULD default to disabled and fail closed")
    void given_noCacheConfig_when_bound_should_defaultDisabledAndFailClosed() {
        assertThat(props.getCache().isEnabled()).isFalse();
        assertThat(props.isFailOpen()).isFalse();
        assertThat(props.getCache().getNegativeTtl()).isLessThan(props.getCache().getTtl());
    }
}
