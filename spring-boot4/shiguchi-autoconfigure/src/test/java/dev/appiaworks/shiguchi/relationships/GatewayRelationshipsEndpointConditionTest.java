package dev.appiaworks.shiguchi.relationships;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * This module sees the GraphQL module at compile time only ({@code compileOnly}), so its tests run
 * without it: exactly the situation of a gateway that does not use GraphQL. The endpoint, which needs
 * the report body from that module, must then not register. The case where it does is covered by the
 * GraphQL starter's end-to-end test.
 */
@DisplayName("GatewayRelationshipsEndpoint conditions")
class GatewayRelationshipsEndpointConditionTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GatewayRelationshipsEndpoint.class);

    @Test
    @DisplayName(
            "GIVEN the graphql module is not on the classpath WHEN the context refreshes SHOULD not register the endpoint")
    void GIVEN_noGraphqlModule_WHEN_contextRefreshes_SHOULD_notRegisterTheEndpoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(GatewayRelationshipsEndpoint.class));
    }

    @Test
    @DisplayName("GIVEN graphql is switched off WHEN the context refreshes SHOULD not register the endpoint")
    void GIVEN_graphqlDisabled_WHEN_contextRefreshes_SHOULD_notRegisterTheEndpoint() {
        runner.withPropertyValues("shiguchi.graphql.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(GatewayRelationshipsEndpoint.class));
    }
}
