package dev.appiaworks.shiguchi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("JWT principal converter")
class JwtPrincipalConverterTest {

    private static final Jwt TOKEN = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("f81d4fae")
            .claim("preferred_username", "alice")
            .claim("email", "alice@example.com")
            .build();

    private static ReactiveJwtAuthenticationConverter namingBy(String claim) {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setPrincipalClaimName(claim);
        return converter;
    }

    private static ReactiveJwtAuthenticationConverterAdapter adapterNamingBy(String claim) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(claim);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    private static JwtPrincipalConverter resolve(Object... beans) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < beans.length; i++) factory.registerSingleton("converter" + i, beans[i]);
        ObjectProvider<ReactiveJwtAuthenticationConverter> reactive =
                factory.getBeanProvider(ReactiveJwtAuthenticationConverter.class);
        ObjectProvider<Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>>> any =
                factory.getBeanProvider(ResolvableType.forClassWithGenerics(
                        Converter.class,
                        ResolvableType.forClass(Jwt.class),
                        ResolvableType.forClassWithGenerics(Mono.class, AbstractAuthenticationToken.class)));
        return JwtPrincipalConverter.resolve(reactive, any);
    }

    private static String nameOf(JwtPrincipalConverter converter, Jwt jwt) {
        return converter.convert(jwt).map(AbstractAuthenticationToken::getName).block();
    }

    @Test
    @DisplayName("GIVEN no converter bean WHEN a token is converted SHOULD name the caller by sub")
    void given_noBean_when_converted_should_useSubject() {
        assertThat(nameOf(resolve(), TOKEN)).isEqualTo("f81d4fae");
    }

    @Test
    @DisplayName(
            "GIVEN a ReactiveJwtAuthenticationConverter bean WHEN a token is converted SHOULD use its principal claim")
    void given_reactiveBean_when_converted_should_useItsClaim() {
        assertThat(nameOf(resolve(namingBy("preferred_username")), TOKEN)).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN only an adapter bean WHEN a token is converted SHOULD use it")
    void given_onlyAdapterBean_when_converted_should_useIt() {
        assertThat(nameOf(resolve(adapterNamingBy("email")), TOKEN)).isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName(
            "GIVEN a ReactiveJwtAuthenticationConverter and an adapter bean WHEN resolved SHOULD prefer the former")
    void given_reactiveAndAdapterBeans_when_resolved_should_preferReactive() {
        assertThat(nameOf(resolve(adapterNamingBy("email"), namingBy("preferred_username")), TOKEN))
                .isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN two adapter beans WHEN resolved SHOULD fall back to sub instead of failing")
    void given_twoAdapterBeans_when_resolved_should_useDefault() {
        assertThat(nameOf(resolve(adapterNamingBy("email"), adapterNamingBy("preferred_username")), TOKEN))
                .isEqualTo("f81d4fae");
    }

    @Test
    @DisplayName("GIVEN a principal claim the token lacks WHEN converted SHOULD reject it as an invalid bearer token")
    void given_tokenWithoutPrincipalClaim_when_converted_should_reject() {
        Jwt noUsername = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("f81d4fae")
                .claim("scope", List.of("read"))
                .build();

        StepVerifier.create(resolve(namingBy("preferred_username")).convert(noUsername))
                .expectError(InvalidBearerTokenException.class)
                .verify();
    }
}
