package dev.appiaworks.shiguchi.bff.starter;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.BffSecurityContextRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * The bearer path's JWT decoder is Boot's own, from {@code jwk-set-uri}, whether the BFF module
 * is on or off on the Boot 3 gateway: the module must never define a {@code ReactiveJwtDecoder},
 * because Boot's backs off when one exists. Mirrors {@code spring-boot4/shiguchi-bff-starter}.
 */
@DisplayName("BFF module leaves the bearer JWT decoder alone (Boot 3)")
class BffJwtDecoderEndToEndTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {}

    @Test
    @DisplayName(
            "GIVEN Boot's jwk-set-uri decoder WHEN the module is enabled SHOULD leave the same decoder beans as when it is off")
    void given_bootDecoder_when_enabled_should_keepDecoderBeans() {
        List<String> off = decoderBeans(false);
        List<String> on = decoderBeans(true);

        assertThat(off).hasSize(1);
        assertThat(on).isEqualTo(off);
    }

    private static List<String> decoderBeans(boolean bff) {
        List<String> properties = new ArrayList<>(List.of(
                "server.port=0",
                "shiguchi.ui.enabled=false",
                "shiguchi.graphql.enabled=false",
                "shiguchi.openapi.enabled=false",
                "shiguchi.security.mode=AUTHENTICATED",
                // Never fetched: the decoder reads the key set on first use.
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"));
        if (bff) {
            properties.addAll(List.of(
                    "shiguchi.bff.enabled=true",
                    "shiguchi.bff.providers.idp.issuer-uri=https://id.example.com",
                    "shiguchi.bff.providers.idp.client-id=gateway",
                    "shiguchi.bff.providers.idp.client-secret=secret",
                    "shiguchi.bff.applications.app.origins=https://app.example.com",
                    "shiguchi.bff.applications.app.providers=idp"));
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(properties.toArray(String[]::new))
                .run()) {
            assertThat(context.getBeanNamesForType(BffSecurityContextRepository.class))
                    .hasSize(bff ? 1 : 0);
            return List.of(context.getBeanNamesForType(ReactiveJwtDecoder.class));
        }
    }
}
