package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition.GraphQlOperationType;
import dev.appiaworks.shiguchi.security.CookieSessionWatch;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import io.grpc.MethodDescriptor;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.graphql.execution.SubscriptionPublisherException;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("Live queries end with the credential they were opened with")
class GraphQlLiveInterceptorBindingTest {

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName(
            "GIVEN a live query of a bearer caller WHEN the token reaches exp SHOULD end with a token expired error")
    void given_bearerLiveQuery_when_tokenExpires_should_endStream() {
        GrpcRouteRegistry registry = mock(GrpcRouteRegistry.class);
        when(registry.findByGraphQlName("item"))
                .thenReturn(List.of(
                        RouteDefinition.builder("pkg.Store", "Get", MethodDescriptor.MethodType.UNARY, "localhost:1")
                                .graphQl("item", GraphQlOperationType.QUERY)
                                .build()));
        GatewayProperties properties = new GatewayProperties();
        var live = properties.getGraphql().getLive();
        ObjectProvider<CookieSessionWatch> watches = mock(ObjectProvider.class);
        CookieSessionBindingInterceptor binding = new CookieSessionBindingInterceptor(watches, properties);
        ObjectProvider<CookieSessionBindingInterceptor> bindingProvider = mock(ObjectProvider.class);
        when(bindingProvider.getIfAvailable()).thenReturn(binding);
        GraphQlLiveInterceptor interceptor = new GraphQlLiveInterceptor(
                new GraphQlLiveDependencies(registry),
                new GraphQlLiveInvalidations(),
                new GraphQlLiveStreams(5, 10),
                live,
                bindingProvider);
        WebGraphQlRequest request = new WebSocketGraphQlRequest(
                URI.create("ws://gw/graphql/ws"),
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of(),
                Map.of("query", "query Q @live { item { id } }"),
                "1",
                Locale.ENGLISH,
                mock(WebSocketSessionInfo.class));
        WebGraphQlInterceptor.Chain chain = r -> {
            ExecutionResult result = ExecutionResultImpl.newExecutionResult()
                    .data(Map.of("item", Map.of("id", "1")))
                    .build();
            return Mono.just(new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(r.toExecutionInput(), result)));
        };
        Instant now = Instant.now();
        var auth = new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(1))
                .build());

        WebGraphQlResponse response = interceptor
                .intercept(request, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
                .block(Duration.ofSeconds(5));

        @SuppressWarnings("unchecked")
        Flux<ExecutionResult> stream = Flux.from((org.reactivestreams.Publisher<ExecutionResult>) response.getData());
        StepVerifier.create(stream)
                .expectNextCount(1)
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(SubscriptionPublisherException.class);
                    assertThat(((SubscriptionPublisherException) e).getErrors().toString())
                            .contains("token expired");
                })
                .verify(Duration.ofSeconds(5));
    }
}
