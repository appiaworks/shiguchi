package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.gateway.UpstreamCredentialSource;
import graphql.GraphQLContext;
import io.grpc.Metadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

@DisplayName("GraphQL upstream metadata for a socket bearer")
class GraphQlMetadataSocketTokenTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static GraphQLContext contextWithToken(String token) {
        GraphQLContext context = GraphQLContext.newContext().build();
        if (token != null) context.put(ConnectionInitAuthInterceptor.TOKEN_CONTEXT_KEY, token);
        return context;
    }

    @SuppressWarnings("unchecked")
    private static GrpcMetadataPopulator populator(UpstreamCredentialSource source) {
        ObjectProvider<UpstreamCredentialSource> sources = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(sources.stream())
                .thenAnswer(i -> source == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(source));
        return new GrpcMetadataPopulator(new GatewayProperties(), sources);
    }

    private static Metadata build(GraphQLContext context, GrpcMetadataPopulator populator) {
        return GraphQlMetadata.build(context, populator)
                .contextWrite(
                        ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken("bob", "x")))
                .block();
    }

    @Test
    @DisplayName(
            "GIVEN a socket token and no credential source WHEN metadata is built THEN it is sent as authorization")
    void GIVEN_socket_token_and_no_source_WHEN_building_SHOULD_forward_the_bearer() {
        Metadata md = build(contextWithToken("tok"), populator(null));

        assertThat(md.get(AUTHORIZATION)).isEqualTo("Bearer tok");
    }

    @Test
    @DisplayName("GIVEN a socket token and a credential source WHEN metadata is built THEN the token is not sent")
    void GIVEN_socket_token_and_source_WHEN_building_SHOULD_not_forward_the_bearer() {
        UpstreamCredentialSource source = (request, auth) -> Mono.just("Bearer exchanged");

        Metadata md = build(contextWithToken("tok"), populator(source));

        assertThat(md.get(AUTHORIZATION)).isNull();
    }

    @Test
    @DisplayName("GIVEN no socket token WHEN metadata is built THEN no authorization is sent")
    void GIVEN_no_socket_token_WHEN_building_SHOULD_send_no_authorization() {
        Metadata md = build(contextWithToken(null), populator(null));

        assertThat(md.get(AUTHORIZATION)).isNull();
    }
}
