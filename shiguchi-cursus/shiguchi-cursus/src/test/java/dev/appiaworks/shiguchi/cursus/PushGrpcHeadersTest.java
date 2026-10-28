package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("Push gRPC request headers")
class PushGrpcHeadersTest {

    private static final Metadata.Key<String> AUTHORIZATION = PushGrpcHeaders.AUTHORIZATION;

    // ---- bearer() tests ----

    @ParameterizedTest(name = "header [{0}] yields token [{1}]")
    @MethodSource("authorizationHeaders")
    @DisplayName("an authorization header yields its bearer token, or nothing when it carries none")
    void given_authorization_header_when_extracting_bearer_should_return_token_or_null(String header, String expected) {
        assertThat(PushGrpcHeaders.bearer(header)).isEqualTo(expected);
    }

    static Stream<Arguments> authorizationHeaders() {
        return Stream.of(
                Arguments.of("Bearer abc", "abc"),
                Arguments.of("bearer abc", "abc"),
                Arguments.of("  Bearer  abc  ", "abc"),
                Arguments.of("Basic abc", null),
                Arguments.of("Bearer ", null),
                Arguments.of(null, null));
    }

    // ---- caller() tests ----

    @Test
    @DisplayName("a call with no request metadata in context has no caller")
    void given_no_metadata_in_context_when_resolving_caller_should_be_empty() {
        PushTokenAuthenticator tokens = new PushTokenAuthenticator(null, null);

        StepVerifier.create(PushGrpcHeaders.caller(tokens))
                .assertNext(caller -> assertThat(caller).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("a call with a valid bearer token resolves to the authenticated subject")
    void given_valid_bearer_token_when_resolving_caller_should_return_authentication() throws Exception {
        Jwt jwt = Jwt.withTokenValue("good")
                .header("alg", "none")
                .subject("alice")
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        ReactiveJwtDecoder decoder = token -> Mono.just(jwt);
        PushTokenAuthenticator tokens = new PushTokenAuthenticator(decoder, null);

        Mono<Optional<Authentication>> caller = Context.current()
                .withValue(PushGrpcHeaders.METADATA, metadataWith("Bearer good"))
                .call(() -> PushGrpcHeaders.caller(tokens));

        StepVerifier.create(caller)
                .assertNext(optional -> assertThat(optional)
                        .hasValueSatisfying(auth -> assertThat(auth.getName()).isEqualTo("alice")))
                .verifyComplete();
    }

    @Test
    @DisplayName("a call with a rejected bearer token fails as unauthorized")
    void given_rejected_bearer_token_when_resolving_caller_should_error_unauthorized() throws Exception {
        ReactiveJwtDecoder decoder = token -> Mono.error(new BadJwtException("nope"));
        PushTokenAuthenticator tokens = new PushTokenAuthenticator(decoder, null);

        Mono<Optional<Authentication>> caller = Context.current()
                .withValue(PushGrpcHeaders.METADATA, metadataWith("Bearer bad"))
                .call(() -> PushGrpcHeaders.caller(tokens));

        StepVerifier.create(caller)
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PushException.class);
                    assertThat(((PushException) e).status().value()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    @DisplayName("a call without an authorization header has no caller")
    void given_metadata_without_authorization_when_resolving_caller_should_be_empty() throws Exception {
        PushTokenAuthenticator tokens =
                new PushTokenAuthenticator(token -> Mono.error(new BadJwtException("unused")), null);

        Mono<Optional<Authentication>> caller = Context.current()
                .withValue(PushGrpcHeaders.METADATA, new Metadata())
                .call(() -> PushGrpcHeaders.caller(tokens));

        StepVerifier.create(caller)
                .assertNext(optional -> assertThat(optional).isEmpty())
                .verifyComplete();
    }

    // ---- interceptCall() test ----

    @Test
    @DisplayName("an intercepted call exposes its request metadata to the handler through the context")
    void given_intercepted_call_when_handled_should_expose_same_metadata_in_context() {
        Metadata headers = metadataWith("Bearer whatever");
        AtomicReference<Metadata> seen = new AtomicReference<>();
        ServerCallHandler<String, String> handler = (call, received) -> {
            seen.set(PushGrpcHeaders.METADATA.get());
            return new ServerCall.Listener<>() {};
        };

        new PushGrpcHeaders().interceptCall(noOpCall(), headers, handler);

        assertThat(seen.get()).isSameAs(headers);
    }

    private static Metadata metadataWith(String authorization) {
        Metadata metadata = new Metadata();
        metadata.put(AUTHORIZATION, authorization);
        return metadata;
    }

    private static ServerCall<String, String> noOpCall() {
        return new ServerCall<>() {
            @Override
            public void request(int numMessages) {}

            @Override
            public void sendHeaders(Metadata headers) {}

            @Override
            public void sendMessage(String message) {}

            @Override
            public void close(Status status, Metadata trailers) {}

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public MethodDescriptor<String, String> getMethodDescriptor() {
                return null;
            }
        };
    }
}
