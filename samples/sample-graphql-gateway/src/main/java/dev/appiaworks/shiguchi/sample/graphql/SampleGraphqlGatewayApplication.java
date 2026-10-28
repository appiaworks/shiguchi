package dev.appiaworks.shiguchi.sample.graphql;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demonstrates the GraphQL gateway feature: gRPC services exposed as a GraphQL API.
 *
 * Methods annotated with @GqlSchema (or the (shiguchi.api.graphql) option) become GraphQL
 * queries/mutations. Fields annotated with @GqlField control the GraphQL schema shape.
 *
 * Try: http://localhost:8080/graphiql
 */
@SpringBootApplication
public class SampleGraphqlGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleGraphqlGatewayApplication.class, args);
    }
}
