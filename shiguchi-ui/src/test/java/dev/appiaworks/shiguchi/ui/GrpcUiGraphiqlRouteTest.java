package dev.appiaworks.shiguchi.ui;

import static org.mockito.Mockito.mock;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("GraphiQL route of the console")
class GrpcUiGraphiqlRouteTest {

    private WebTestClient client(boolean graphqlEnabled, MockEnvironment environment) {
        var props = new GatewayProperties();
        props.getGraphql().setEnabled(graphqlEnabled);
        var router = new GrpcUiConfig().grpcUiRouterFunction(props, mock(GrpcUiController.class), environment);
        return WebTestClient.bindToRouterFunction(router).build();
    }

    @Test
    @DisplayName("GIVEN GraphQL on and no switch WHEN /graphiql is fetched SHOULD redirect to the bundled page")
    void GIVEN_graphql_on_WHEN_fetched_SHOULD_redirect() {
        client(true, new MockEnvironment())
                .get()
                .uri("/graphiql")
                .exchange()
                .expectStatus()
                .isTemporaryRedirect()
                .expectHeader()
                .valueEquals("Location", "/grpc-ui/graphiql.html");
    }

    @Test
    @DisplayName("GIVEN dgs.graphql.graphiql.enabled=false WHEN /graphiql is fetched SHOULD not be served")
    void GIVEN_dgs_flag_off_WHEN_fetched_SHOULD_not_serve() {
        client(true, new MockEnvironment().withProperty("dgs.graphql.graphiql.enabled", "false"))
                .get()
                .uri("/graphiql")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("GIVEN GraphQL off WHEN /graphiql is fetched SHOULD not be served")
    void GIVEN_graphql_off_WHEN_fetched_SHOULD_not_serve() {
        client(false, new MockEnvironment())
                .get()
                .uri("/graphiql")
                .exchange()
                .expectStatus()
                .isNotFound();
    }
}
