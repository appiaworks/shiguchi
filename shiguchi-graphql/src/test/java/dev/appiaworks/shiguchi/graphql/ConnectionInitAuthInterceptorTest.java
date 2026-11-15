package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.convert.converter.Converter;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.util.LinkedMultiValueMap;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("GraphQL WebSocket connection_init authentication")
class ConnectionInitAuthInterceptorTest {

    private final Map<String, Object> attributes = new HashMap<>();
    private final WebSocketSessionInfo session = mock(WebSocketSessionInfo.class);

    private final ReactiveJwtDecoder decoder = token -> token.equals("good")
            ? Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("alice")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build())
            : Mono.error(new BadJwtException("bad"));

    ConnectionInitAuthInterceptorTest() {
        when(session.getAttributes()).thenReturn(attributes);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private ConnectionInitAuthInterceptor interceptor(ReactiveJwtDecoder decoder) {
        return interceptor(decoder, false);
    }

    private ConnectionInitAuthInterceptor interceptor(ReactiveJwtDecoder decoder, boolean requireAuth) {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().getSurfaces().getGraphql().setRequireAuth(requireAuth);
        ObjectProvider<ReactiveJwtAuthenticationConverter> reactive = provider(null);
        ObjectProvider<Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>>> any = provider(null);
        return new ConnectionInitAuthInterceptor(provider(decoder), reactive, any, properties);
    }

    private WebSocketGraphQlRequest request() {
        return new WebSocketGraphQlRequest(
                URI.create("ws://gw/graphql/ws"),
                new HttpHeaders(),
                new LinkedMultiValueMap<>(),
                Map.of(),
                Map.of("query", "subscription { x }"),
                "1",
                Locale.ENGLISH,
                session);
    }

    private Authentication callerSeenByChain(ConnectionInitAuthInterceptor interceptor) {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        WebGraphQlInterceptor.Chain chain = r -> ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .doOnNext(seen::set)
                .then(Mono.just(mock(WebGraphQlResponse.class)));
        interceptor.intercept(request(), chain).block();
        return seen.get();
    }

    @Test
    @DisplayName("GIVEN a bearer token in connection_init WHEN operations run THEN they run as that caller")
    void GIVEN_bearer_in_payload_WHEN_operation_runs_SHOULD_authenticate_the_caller() {
        ConnectionInitAuthInterceptor interceptor = interceptor(decoder);

        StepVerifier.create(interceptor.handleConnectionInitialization(session, Map.of("Authorization", "Bearer good")))
                .verifyComplete();

        Authentication seen = callerSeenByChain(interceptor);
        assertThat(seen).isNotNull();
        assertThat(seen.getName()).isEqualTo("alice");
    }

    @Test
    @DisplayName(
            "GIVEN a bearer token in connection_init WHEN operations run THEN the token reaches the operation context")
    void GIVEN_bearer_in_payload_WHEN_operation_runs_SHOULD_carry_the_token_in_the_context() {
        ConnectionInitAuthInterceptor interceptor = interceptor(decoder);
        StepVerifier.create(interceptor.handleConnectionInitialization(session, Map.of("Authorization", "Bearer good")))
                .verifyComplete();

        WebSocketGraphQlRequest request = request();
        interceptor
                .intercept(request, r -> Mono.just(mock(WebGraphQlResponse.class)))
                .block();

        assertThat(request.toExecutionInput()
                        .getGraphQLContext()
                        .<String>get(ConnectionInitAuthInterceptor.TOKEN_CONTEXT_KEY))
                .isEqualTo("good");
    }

    @Test
    @DisplayName("GIVEN no credential in connection_init WHEN operations run THEN the context carries no token")
    void GIVEN_no_credential_WHEN_operation_runs_SHOULD_carry_no_token() {
        ConnectionInitAuthInterceptor interceptor = interceptor(decoder);

        WebSocketGraphQlRequest request = request();
        interceptor
                .intercept(request, r -> Mono.just(mock(WebGraphQlResponse.class)))
                .block();

        assertThat(request.toExecutionInput()
                        .getGraphQLContext()
                        .hasKey(ConnectionInitAuthInterceptor.TOKEN_CONTEXT_KEY))
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a lower-case header name WHEN connecting THEN the token is still read")
    void GIVEN_lowercase_key_WHEN_connecting_SHOULD_read_token() {
        ConnectionInitAuthInterceptor interceptor = interceptor(decoder);

        StepVerifier.create(interceptor.handleConnectionInitialization(session, Map.of("authorization", "bearer good")))
                .verifyComplete();

        assertThat(callerSeenByChain(interceptor).getName()).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN a rejected token WHEN connecting THEN the connection fails")
    void GIVEN_rejected_token_WHEN_connecting_SHOULD_fail() {
        StepVerifier.create(interceptor(decoder)
                        .handleConnectionInitialization(session, Map.of("Authorization", "Bearer nope")))
                .expectError(InvalidBearerTokenException.class)
                .verify();

        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("GIVEN no credential in the payload WHEN connecting THEN the socket stays anonymous")
    void GIVEN_no_credential_WHEN_connecting_SHOULD_not_authenticate() {
        ConnectionInitAuthInterceptor interceptor = interceptor(decoder);

        StepVerifier.create(interceptor.handleConnectionInitialization(session, Map.of()))
                .verifyComplete();
        StepVerifier.create(interceptor.handleConnectionInitialization(session, null))
                .verifyComplete();

        assertThat(callerSeenByChain(interceptor)).isNull();
    }

    @Test
    @DisplayName("GIVEN no JWT decoder WHEN a token arrives THEN it is ignored")
    void GIVEN_no_decoder_WHEN_token_arrives_SHOULD_ignore_it() {
        ConnectionInitAuthInterceptor interceptor = interceptor(null);

        StepVerifier.create(interceptor.handleConnectionInitialization(session, Map.of("Authorization", "Bearer good")))
                .verifyComplete();

        assertThat(callerSeenByChain(interceptor)).isNull();
    }

    private WebGraphQlResponse runUnderGate(ConnectionInitAuthInterceptor interceptor, Authentication handshake) {
        WebGraphQlResponse ok = mock(WebGraphQlResponse.class);
        WebGraphQlInterceptor.Chain chain = r -> Mono.just(ok);
        Mono<WebGraphQlResponse> run = interceptor.intercept(request(), chain);
        if (handshake != null) {
            run = run.contextWrite(ReactiveSecurityContextHolder.withAuthentication(handshake));
        }
        return run.block();
    }

    @Test
    @DisplayName("GIVEN a gated surface WHEN an anonymous socket runs an operation THEN it is refused")
    void GIVEN_gated_surface_WHEN_anonymous_socket_SHOULD_refuse() {
        WebGraphQlResponse response = runUnderGate(interceptor(decoder, true), null);

        assertThat(response.getErrors()).hasSize(1);
        assertThat(response.getErrors().get(0).getExtensions()).containsEntry("code", "unauthenticated");
    }

    @Test
    @DisplayName("GIVEN a gated surface WHEN connection_init authenticated the socket THEN operations run")
    void GIVEN_gated_surface_WHEN_connection_init_authenticated_SHOULD_run() {
        ConnectionInitAuthInterceptor interceptor = interceptor(decoder, true);
        StepVerifier.create(interceptor.handleConnectionInitialization(session, Map.of("Authorization", "Bearer good")))
                .verifyComplete();

        assertThat(callerSeenByChain(interceptor).getName()).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN a gated surface WHEN the handshake authenticated the caller THEN operations run")
    void GIVEN_gated_surface_WHEN_handshake_authenticated_SHOULD_run() {
        Authentication handshake =
                new org.springframework.security.authentication.TestingAuthenticationToken("bob", "n/a", "ROLE_USER");

        WebGraphQlResponse response = runUnderGate(interceptor(decoder, true), handshake);

        assertThat(response.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an open surface WHEN an anonymous socket runs an operation THEN it runs")
    void GIVEN_open_surface_WHEN_anonymous_socket_SHOULD_run() {
        assertThat(runUnderGate(interceptor(decoder, false), null).getErrors()).isEmpty();
    }
}
