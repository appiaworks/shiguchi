package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import graphql.ExecutionInput;
import graphql.GraphQLContext;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("GraphQL request context relay")
class GraphQlRequestContextTest {

    private static Mono<String> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(a -> a.getName())
                .defaultIfEmpty("anonymous");
    }

    @Test
    @DisplayName("GIVEN a saved request context WHEN a Mono runs within it SHOULD see the request's security context")
    void given_savedContext_when_monoWithin_should_seeSecurityContext() {
        GraphQLContext ctx = GraphQLContext.newContext().build();
        GraphQlRequestContext.save(
                ctx, ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken("bob", "n/a")));

        StepVerifier.create(GraphQlRequestContext.within(ctx, currentUser()))
                .expectNext("bob")
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a saved request context WHEN a Flux runs within it SHOULD see the request's security context")
    void given_savedContext_when_fluxWithin_should_seeSecurityContext() {
        GraphQLContext ctx = GraphQLContext.newContext().build();
        GraphQlRequestContext.save(
                ctx, ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken("bob", "n/a")));

        StepVerifier.create(GraphQlRequestContext.within(ctx, currentUser().flux()))
                .expectNext("bob")
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN nothing saved or no GraphQL context WHEN running within SHOULD return the publisher unchanged")
    void given_nothingSaved_when_within_should_returnPublisherUnchanged() {
        Mono<String> mono = currentUser();
        Flux<String> flux = mono.flux();

        assertThat(GraphQlRequestContext.within(GraphQLContext.newContext().build(), mono))
                .isSameAs(mono);
        assertThat(GraphQlRequestContext.within((GraphQLContext) null, flux)).isSameAs(flux);
    }

    @Test
    @DisplayName(
            "GIVEN a request whose Reactor context holds the exchange WHEN the interceptor runs SHOULD save the context and the exchange for the fetchers")
    void given_exchangeInReactorContext_when_intercepted_should_saveContextAndExchange() {
        WebGraphQlRequest request = mock(WebGraphQlRequest.class);
        WebGraphQlResponse response = mock(WebGraphQlResponse.class);
        WebGraphQlInterceptor.Chain chain = mock(WebGraphQlInterceptor.Chain.class);
        when(chain.next(request)).thenReturn(Mono.just(response));
        @SuppressWarnings("unchecked")
        var callback = org.mockito.ArgumentCaptor.forClass(
                (Class<BiFunction<ExecutionInput, ExecutionInput.Builder, ExecutionInput>>)
                        (Class<?>) BiFunction.class);
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/graphql"));

        StepVerifier.create(new GatewayRequestContextInterceptor()
                        .intercept(request, chain)
                        .contextWrite(c -> c.put(ServerWebExchange.class, exchange)
                                .putAll(ReactiveSecurityContextHolder.withAuthentication(
                                                new TestingAuthenticationToken("bob", "n/a"))
                                        .readOnly())))
                .expectNext(response)
                .verifyComplete();

        org.mockito.Mockito.verify(request).configureExecutionInput(callback.capture());
        ExecutionInput input = ExecutionInput.newExecutionInput("{ a }").build();
        callback.getValue().apply(input, ExecutionInput.newExecutionInput("{ a }"));
        GraphQLContext saved = input.getGraphQLContext();
        assertThat(saved.<ServerWebExchange>get(ServerWebExchange.class)).isSameAs(exchange);
        StepVerifier.create(GraphQlRequestContext.within(saved, currentUser()))
                .expectNext("bob")
                .verifyComplete();
    }
}
