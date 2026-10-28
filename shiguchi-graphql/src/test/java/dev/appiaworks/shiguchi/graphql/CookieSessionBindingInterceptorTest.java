package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.security.CookieAuthenticated;
import dev.appiaworks.shiguchi.security.CookieSessionEnded;
import dev.appiaworks.shiguchi.security.CookieSessionWatch;
import dev.appiaworks.shiguchi.security.CookieSessions;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@DisplayName("Cookie session binding interceptor for GraphQL")
class CookieSessionBindingInterceptorTest {

    private boolean sessionActive = true;
    private final CookieSessions sessions = handle -> Mono.just(sessionActive);
    private final CookieSessionWatch watch = new CookieSessionWatch(sessions, Duration.ofHours(1), null);
    private final CookieSessionBindingInterceptor interceptor = interceptor(watch, true);

    private static CookieSessionBindingInterceptor interceptor(CookieSessionWatch watch, boolean endAtTokenExpiry) {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setEndStreamsAtTokenExpiry(endAtTokenExpiry);
        return new CookieSessionBindingInterceptor(provider(watch), properties);
    }

    private static Authentication bearerCaller(Duration untilExpiry) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("alice")
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plus(untilExpiry))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CookieSessionWatch> provider(CookieSessionWatch watch) {
        ObjectProvider<CookieSessionWatch> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(watch);
        return provider;
    }

    private static Authentication cookieCaller(String handle) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "n/a");
        auth.setDetails(new CookieAuthenticated(null, handle, "a"));
        return auth;
    }

    private static WebGraphQlRequest http() {
        return new WebGraphQlRequest(
                URI.create("http://gw/graphql"),
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of(),
                Map.of("query", "{ __typename }"),
                "1",
                Locale.ENGLISH);
    }

    private static WebGraphQlRequest webSocket() {
        return new WebSocketGraphQlRequest(
                URI.create("ws://gw/graphql/ws"),
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of(),
                Map.of("query", "{ __typename }"),
                "1",
                Locale.ENGLISH,
                mock(WebSocketSessionInfo.class));
    }

    private static WebGraphQlResponse responseWith(WebGraphQlRequest request, Object data) {
        ExecutionResult result =
                ExecutionResultImpl.newExecutionResult().data(data).build();
        return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(request.toExecutionInput(), result));
    }

    private Mono<WebGraphQlResponse> run(WebGraphQlRequest request, Authentication auth, WebGraphQlResponse next) {
        WebGraphQlInterceptor.Chain chain = r -> Mono.just(next);
        Mono<WebGraphQlResponse> call = interceptor.intercept(request, chain);
        return auth == null ? call : call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }

    @Test
    @DisplayName("GIVEN a bearer caller WHEN a subscription runs SHOULD leave the response untouched")
    void given_bearer_caller_when_subscribing_should_leave_response_untouched() {
        WebGraphQlRequest request = webSocket();
        WebGraphQlResponse next = responseWith(request, Flux.never());

        WebGraphQlResponse response = run(request, new TestingAuthenticationToken("alice", "n/a"), next)
                .block();

        assertThat(response).isSameAs(next);
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller on a WebSocket WHEN its session is no longer active SHOULD refuse with the session ended error")
    void given_cookie_websocket_when_session_inactive_should_refuse_operation() {
        sessionActive = false;
        WebGraphQlRequest request = webSocket();
        AtomicBoolean reached = new AtomicBoolean();
        WebGraphQlInterceptor.Chain chain = r -> {
            reached.set(true);
            return Mono.just(responseWith(request, Map.of()));
        };

        WebGraphQlResponse response = interceptor
                .intercept(request, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(cookieCaller("h1")))
                .block();

        assertThat(reached).isFalse();
        assertThat(response.getErrors()).hasSize(1);
        assertThat(response.getErrors().get(0).getMessage()).isEqualTo("session ended");
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller on HTTP WHEN its session is not active SHOULD still run the request, which just loaded it")
    void given_cookie_http_request_should_not_ask_the_store_again() {
        sessionActive = false;
        WebGraphQlRequest request = http();
        WebGraphQlResponse next = responseWith(request, Map.of("ok", true));

        WebGraphQlResponse response = run(request, cookieCaller("h1"), next).block();

        assertThat(response.getErrors()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN a cookie caller's subscription WHEN this instance ends the session SHOULD end the stream with the session ended error")
    void given_cookie_subscription_when_session_ended_should_error_the_stream() {
        WebGraphQlRequest request = webSocket();
        Sinks.Many<ExecutionResult> live = Sinks.many().unicast().onBackpressureBuffer();
        WebGraphQlResponse next = responseWith(request, live.asFlux());
        WebGraphQlResponse response = run(request, cookieCaller("h1"), next).block();
        Flux<ExecutionResult> results = response.getData();

        StepVerifier.create(results)
                .then(() -> live.tryEmitNext(ExecutionResultImpl.newExecutionResult()
                        .data(Map.of("n", 1))
                        .build()))
                .expectNextCount(1)
                .then(() -> watch.onSessionEnded(new CookieSessionEnded("h1")))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(SubscriptionPublisherException.class);
                    List<graphql.GraphQLError> errors = ((SubscriptionPublisherException) error).getErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getMessage()).isEqualTo("session ended");
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN an application without the session watch WHEN an operation runs SHOULD pass it on unchanged")
    void given_no_watch_when_operation_runs_should_pass_through() {
        CookieSessionBindingInterceptor bare = interceptor(null, true);
        WebGraphQlRequest request = webSocket();
        WebGraphQlResponse next = responseWith(request, Map.of());

        WebGraphQlResponse response = bare.intercept(request, r -> Mono.just(next))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(cookieCaller("h1")))
                .block();

        assertThat(response).isSameAs(next);
    }

    @Test
    @DisplayName(
            "GIVEN a bearer caller's subscription WHEN the token reaches its expiry SHOULD end the stream with the token expired error")
    void given_bearer_subscription_when_token_expires_should_error_the_stream() {
        WebGraphQlRequest request = webSocket();
        AtomicBoolean cancelled = new AtomicBoolean();
        WebGraphQlResponse next =
                responseWith(request, Flux.<ExecutionResult>never().doOnCancel(() -> cancelled.set(true)));
        WebGraphQlResponse response =
                run(request, bearerCaller(Duration.ofMillis(300)), next).block();
        Flux<ExecutionResult> results = response.getData();

        StepVerifier.create(results)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(SubscriptionPublisherException.class);
                    List<graphql.GraphQLError> errors = ((SubscriptionPublisherException) error).getErrors();
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getMessage()).isEqualTo("token expired");
                    assertThat(errors.get(0).getExtensions()).containsEntry("code", "unauthenticated");
                })
                .verify(Duration.ofSeconds(5));
        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a bearer caller's subscription WHEN the token is already past expiry SHOULD end the stream at once")
    void given_bearer_subscription_with_expired_token_should_end_at_once() {
        WebGraphQlRequest request = http();
        WebGraphQlResponse next = responseWith(request, Flux.<ExecutionResult>never());
        WebGraphQlResponse response =
                run(request, bearerCaller(Duration.ofSeconds(-5)), next).block();
        Flux<ExecutionResult> results = response.getData();

        StepVerifier.create(results)
                .expectError(SubscriptionPublisherException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN a bearer caller on a WebSocket WHEN its token is past expiry SHOULD refuse with the token expired error")
    void given_bearer_websocket_when_token_expired_should_refuse_operation() {
        WebGraphQlRequest request = webSocket();
        AtomicBoolean reached = new AtomicBoolean();
        WebGraphQlInterceptor.Chain chain = r -> {
            reached.set(true);
            return Mono.just(responseWith(request, Map.of()));
        };

        WebGraphQlResponse response = interceptor
                .intercept(request, chain)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(bearerCaller(Duration.ofSeconds(-1))))
                .block();

        assertThat(reached).isFalse();
        assertThat(response.getErrors()).hasSize(1);
        assertThat(response.getErrors().get(0).getMessage()).isEqualTo("token expired");
    }

    @Test
    @DisplayName("GIVEN a bearer caller on HTTP WHEN its token is past expiry SHOULD still run the request")
    void given_bearer_http_request_with_expired_token_should_run() {
        WebGraphQlRequest request = http();
        WebGraphQlResponse next = responseWith(request, Map.of("ok", true));

        WebGraphQlResponse response =
                run(request, bearerCaller(Duration.ofSeconds(-1)), next).block();

        assertThat(response.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a bearer caller on a WebSocket WHEN its token is still valid SHOULD run the operation")
    void given_bearer_websocket_with_valid_token_should_run() {
        WebGraphQlRequest request = webSocket();
        WebGraphQlResponse next = responseWith(request, Map.of("ok", true));

        WebGraphQlResponse response =
                run(request, bearerCaller(Duration.ofHours(1)), next).block();

        assertThat(response.getErrors()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN end-streams-at-token-expiry is off WHEN a bearer token expired SHOULD leave the operation untouched")
    void given_property_off_when_token_expired_should_pass_through() {
        WebGraphQlRequest request = webSocket();
        WebGraphQlResponse next = responseWith(request, Flux.never());

        WebGraphQlResponse response = interceptor(watch, false)
                .intercept(request, r -> Mono.just(next))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(bearerCaller(Duration.ofSeconds(-1))))
                .block();

        assertThat(response).isSameAs(next);
    }

    @Test
    @DisplayName("GIVEN a cookie caller carrying a JWT WHEN the token expired SHOULD follow the session, not the token")
    void given_cookie_caller_with_expired_token_should_not_be_token_bound() {
        WebGraphQlRequest request = webSocket();
        Sinks.Many<ExecutionResult> live = Sinks.many().unicast().onBackpressureBuffer();
        WebGraphQlResponse next = responseWith(request, live.asFlux());
        JwtAuthenticationToken auth = (JwtAuthenticationToken) bearerCaller(Duration.ofSeconds(-5));
        auth.setDetails(new CookieAuthenticated(null, "h1", "a"));

        WebGraphQlResponse response = run(request, auth, next).block();
        Flux<ExecutionResult> results = response.getData();

        StepVerifier.create(results)
                .then(() -> live.tryEmitNext(ExecutionResultImpl.newExecutionResult()
                        .data(Map.of("n", 1))
                        .build()))
                .expectNextCount(1)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN an application without the session watch WHEN a bearer token expires SHOULD still end the subscription")
    void given_no_watch_when_bearer_token_expires_should_end_subscription() {
        WebGraphQlRequest request = webSocket();
        WebGraphQlResponse next = responseWith(request, Flux.<ExecutionResult>never());

        WebGraphQlResponse response = interceptor(null, true)
                .intercept(request, r -> Mono.just(next))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(bearerCaller(Duration.ofMillis(200))))
                .block();
        Flux<ExecutionResult> results = response.getData();

        StepVerifier.create(results)
                .expectError(SubscriptionPublisherException.class)
                .verify(Duration.ofSeconds(5));
    }
}
