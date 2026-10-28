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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * BDD-style unit tests for {@link DataLoaderChainingInterceptor}.
 *
 * <p>The interceptor's only job is to stamp a GraphQLContext flag onto the execution input and
 * pass the request through to the rest of the chain unchanged. {@link WebGraphQlRequest} is
 * mocked to capture the {@code configureExecutionInput} callback; a real {@link GraphQLContext}
 * builder is used so the flag can actually be observed.
 */
@DisplayName("DataLoaderChainingInterceptor")
class DataLoaderChainingInterceptorTest {

    private final DataLoaderChainingInterceptor interceptor = new DataLoaderChainingInterceptor();

    @Test
    @DisplayName(
            "Given a request, when intercept called, should register a configureExecutionInput callback that flags data loader chaining")
    void given_request_when_intercept_should_registerChainingFlagCallback() {
        // Arrange
        WebGraphQlRequest request = mock(WebGraphQlRequest.class);
        WebGraphQlResponse response = mock(WebGraphQlResponse.class);
        WebGraphQlInterceptor.Chain chain = mock(WebGraphQlInterceptor.Chain.class);
        when(chain.next(request)).thenReturn(Mono.just(response));

        @SuppressWarnings("unchecked")
        var callbackCaptor = org.mockito.ArgumentCaptor.forClass(
                (Class<BiFunction<ExecutionInput, ExecutionInput.Builder, ExecutionInput>>)
                        (Class<?>) BiFunction.class);

        // Act
        Mono<WebGraphQlResponse> result = interceptor.intercept(request, chain);

        // Assert
        org.mockito.Mockito.verify(request).configureExecutionInput(callbackCaptor.capture());

        ExecutionInput seedInput =
                ExecutionInput.newExecutionInput("{ __typename }").build();
        ExecutionInput.Builder builder = ExecutionInput.newExecutionInput("{ __typename }");
        ExecutionInput configured = callbackCaptor.getValue().apply(seedInput, builder);

        assertThat(configured.getGraphQLContext().<Boolean>get("__GJ_enable_data_loader_chaining"))
                .isTrue();

        StepVerifier.create(result).expectNext(response).verifyComplete();
    }

    @Test
    @DisplayName(
            "Given the configureExecutionInput callback runs, when applied to an existing GraphQLContext, should preserve pre-existing context entries")
    void given_callbackApplied_when_contextAlreadyHasEntries_should_preserveExistingEntries() {
        // Arrange
        WebGraphQlRequest request = mock(WebGraphQlRequest.class);
        WebGraphQlInterceptor.Chain chain = mock(WebGraphQlInterceptor.Chain.class);
        when(chain.next(request)).thenReturn(Mono.just(mock(WebGraphQlResponse.class)));

        @SuppressWarnings("unchecked")
        var callbackCaptor = org.mockito.ArgumentCaptor.forClass(
                (Class<BiFunction<ExecutionInput, ExecutionInput.Builder, ExecutionInput>>)
                        (Class<?>) BiFunction.class);

        interceptor.intercept(request, chain);
        org.mockito.Mockito.verify(request).configureExecutionInput(callbackCaptor.capture());

        ExecutionInput seedInput = ExecutionInput.newExecutionInput("{ __typename }")
                .graphQLContext(ctx -> ctx.put("preExisting", "kept"))
                .build();
        ExecutionInput.Builder builder = ExecutionInput.newExecutionInput("{ __typename }")
                .graphQLContext(ctx -> ctx.put("preExisting", "kept"));

        // Act
        ExecutionInput configured = callbackCaptor.getValue().apply(seedInput, builder);

        // Assert — both the pre-existing entry and the new chaining flag are present
        assertThat(configured.getGraphQLContext().<String>get("preExisting")).isEqualTo("kept");
        assertThat(configured.getGraphQLContext().<Boolean>get("__GJ_enable_data_loader_chaining"))
                .isTrue();
    }

    @Test
    @DisplayName(
            "Given the downstream chain returns a response, when intercept called, should pass it through unchanged")
    void given_downstreamChainReturnsResponse_when_intercept_should_passThroughUnchanged() {
        // Arrange
        WebGraphQlRequest request = mock(WebGraphQlRequest.class);
        WebGraphQlResponse response = mock(WebGraphQlResponse.class);
        WebGraphQlInterceptor.Chain chain = mock(WebGraphQlInterceptor.Chain.class);
        when(chain.next(request)).thenReturn(Mono.just(response));

        // Act
        Mono<WebGraphQlResponse> result = interceptor.intercept(request, chain);

        // Assert
        StepVerifier.create(result).expectNext(response).verifyComplete();
    }

    @Test
    @DisplayName(
            "Given the downstream chain errors, when intercept called, should propagate the error rather than swallow it")
    void given_downstreamChainErrors_when_intercept_should_propagateError() {
        // Arrange
        WebGraphQlRequest request = mock(WebGraphQlRequest.class);
        WebGraphQlInterceptor.Chain chain = mock(WebGraphQlInterceptor.Chain.class);
        RuntimeException boom = new RuntimeException("downstream failure");
        when(chain.next(request)).thenReturn(Mono.error(boom));

        // Act
        Mono<WebGraphQlResponse> result = interceptor.intercept(request, chain);

        // Assert
        StepVerifier.create(result).expectErrorMatches(t -> t == boom).verify();
    }
}
