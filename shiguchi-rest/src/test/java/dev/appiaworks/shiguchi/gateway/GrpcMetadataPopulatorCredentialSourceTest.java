package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Metadata;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

@DisplayName("GrpcMetadataPopulator with an UpstreamCredentialSource")
class GrpcMetadataPopulatorCredentialSourceTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> PRINCIPAL =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);

    private final AtomicInteger asked = new AtomicInteger();

    private UpstreamCredentialSource returning(String value) {
        return (request, authentication) -> {
            asked.incrementAndGet();
            return Mono.justOrEmpty(value);
        };
    }

    private static ObjectProvider<UpstreamCredentialSource> provider(UpstreamCredentialSource... sources) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        for (int i = 0; i < sources.length; i++) factory.registerSingleton("source" + i, sources[i]);
        return factory.getBeanProvider(UpstreamCredentialSource.class);
    }

    private static GrpcMetadataPopulator populator(UpstreamCredentialSource... sources) {
        return new GrpcMetadataPopulator(new GatewayProperties(), provider(sources));
    }

    private static Authentication alice() {
        TestingAuthenticationToken alice = new TestingAuthenticationToken("alice", null, List.of());
        alice.setAuthenticated(true);
        return alice;
    }

    private static Metadata build(GrpcMetadataPopulator populator, ServerHttpRequest request, Authentication auth) {
        Mono<Metadata> metadata = populator.build(request);
        return (auth == null ? metadata : metadata.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .block();
    }

    private static ServerHttpRequest withoutHeader() {
        return MockServerHttpRequest.get("/pkg.Svc/Get").build();
    }

    @Test
    @DisplayName("GIVEN an Authorization header WHEN building metadata SHOULD forward it and never ask the source")
    void given_authorizationHeader_when_building_should_notAskSource() {
        ServerHttpRequest request = MockServerHttpRequest.get("/pkg.Svc/Get")
                .header("Authorization", "Bearer bob-token")
                .build();

        Metadata metadata = build(populator(returning("Bearer session")), request, alice());

        assertThat(metadata.get(AUTHORIZATION)).isEqualTo("Bearer bob-token");
        assertThat(asked).hasValue(0);
    }

    @Test
    @DisplayName("GIVEN no header and an authenticated caller WHEN building metadata SHOULD send the source's value")
    void given_noHeaderAndAuthenticatedCaller_when_building_should_sendSourceValue() {
        Metadata metadata = build(populator(returning("Bearer session")), withoutHeader(), alice());

        assertThat(metadata.get(AUTHORIZATION)).isEqualTo("Bearer session");
        assertThat(metadata.get(PRINCIPAL)).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN no header and an anonymous caller WHEN building metadata SHOULD not ask the source")
    void given_noHeaderAndAnonymousCaller_when_building_should_notAskSource() {
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        Metadata metadata = build(populator(returning("Bearer session")), withoutHeader(), anonymous);

        assertThat(metadata.containsKey(AUTHORIZATION)).isFalse();
        assertThat(asked).hasValue(0);
    }

    @Test
    @DisplayName("GIVEN no security context WHEN building metadata SHOULD send anonymous and not ask the source")
    void given_noSecurityContext_when_building_should_sendAnonymous() {
        Metadata metadata = build(populator(returning("Bearer session")), withoutHeader(), null);

        assertThat(metadata.get(PRINCIPAL)).isEqualTo("anonymous");
        assertThat(metadata.containsKey(AUTHORIZATION)).isFalse();
        assertThat(asked).hasValue(0);
    }

    @Test
    @DisplayName(
            "GIVEN a source with nothing to send WHEN building metadata SHOULD still build it, with no authorization key")
    void given_emptySource_when_building_should_sendNoAuthorization() {
        Metadata metadata = build(populator(returning(null)), withoutHeader(), alice());

        assertThat(metadata).isNotNull();
        assertThat(metadata.containsKey(AUTHORIZATION)).isFalse();
        assertThat(metadata.get(PRINCIPAL)).isEqualTo("alice");
    }

    @Test
    @DisplayName("GIVEN a source that fails WHEN building metadata SHOULD fail rather than send no credential")
    void given_failingSource_when_building_should_fail() {
        UpstreamCredentialSource failing = (request, auth) -> Mono.error(new IllegalStateException("store down"));

        assertThatThrownBy(() -> build(populator(failing), withoutHeader(), alice()))
                .hasMessageContaining("store down");
    }

    @Test
    @DisplayName("GIVEN two source beans WHEN creating the populator SHOULD fail naming them")
    void given_twoSources_when_creating_should_fail() {
        assertThatThrownBy(() -> populator(returning("a"), new SecondSource()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At most one UpstreamCredentialSource")
                .hasMessageContaining(SecondSource.class.getName());
    }

    static final class SecondSource implements UpstreamCredentialSource {
        @Override
        public Mono<String> authorization(ServerHttpRequest request, Authentication authentication) {
            return Mono.empty();
        }
    }

    /** A subclass written against 0.12, on the one-argument constructor. */
    static final class LegacyPopulator extends GrpcMetadataPopulator {
        LegacyPopulator(GatewayProperties properties) {
            super(properties);
        }

        @Override
        public Mono<Metadata> build(ServerHttpRequest request) {
            return super.build(request)
                    .doOnNext(m -> m.put(Metadata.Key.of("x-legacy", Metadata.ASCII_STRING_MARSHALLER), "yes"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyOverride {
        @Bean
        GatewayProperties gatewayProperties() {
            return new GatewayProperties();
        }

        @Bean
        UpstreamCredentialSource source() {
            return (request, auth) -> Mono.just("Bearer session");
        }

        @Bean
        @Primary
        GrpcMetadataPopulator legacyPopulator(GatewayProperties properties) {
            return new LegacyPopulator(properties);
        }
    }

    @Test
    @DisplayName(
            "GIVEN a @Primary subclass on the one-argument constructor WHEN the context starts SHOULD inject that subclass and it still works")
    void given_primaryLegacySubclass_when_contextStarts_should_win() {
        new ApplicationContextRunner()
                .withUserConfiguration(LegacyOverride.class, GrpcMetadataPopulator.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GrpcMetadataPopulator injected = context.getBean(GrpcMetadataPopulator.class);
                    assertThat(injected).isInstanceOf(LegacyPopulator.class);
                    Metadata metadata = build(injected, withoutHeader(), alice());
                    assertThat(metadata.get(Metadata.Key.of("x-legacy", Metadata.ASCII_STRING_MARSHALLER)))
                            .isEqualTo("yes");
                    assertThat(metadata.get(PRINCIPAL)).isEqualTo("alice");
                });
    }

    @Test
    @DisplayName("GIVEN the component registered with a source bean WHEN the context starts SHOULD use the source")
    void given_componentWithSource_when_contextStarts_should_useSource() {
        new ApplicationContextRunner()
                .withBean(GatewayProperties.class)
                .withBean(UpstreamCredentialSource.class, () -> (request, auth) -> Mono.just("Bearer session"))
                .withUserConfiguration(GrpcMetadataPopulator.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Metadata metadata = build(context.getBean(GrpcMetadataPopulator.class), withoutHeader(), alice());
                    assertThat(metadata.get(AUTHORIZATION)).isEqualTo("Bearer session");
                });
    }
}
