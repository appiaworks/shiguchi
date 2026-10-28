package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("BffConfiguration: session.encryption checks")
class BffEncryptionValidationTest {

    private static BffProperties bind(String fixture) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(fixture, new ClassPathResource("bff/" + fixture));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("shiguchi.bff", BffProperties.class)
                .get();
    }

    private static String problem(String fixture) throws IOException {
        BffProperties properties = bind(fixture);
        Throwable thrown = catchThrowable(() -> BffConfiguration.validate(properties));
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        return thrown.getMessage();
    }

    @Test
    @DisplayName("GIVEN store REDIS and no keys WHEN validated SHOULD ask for keys or disabled")
    void given_redis_without_keys_when_validated_should_fail() throws IOException {
        assertThat(problem("encryption-missing.yml"))
                .contains("set session.encryption.keys and session.encryption.active, or"
                        + " session.encryption.disabled: true to accept plaintext tokens");
    }

    @Test
    @DisplayName("GIVEN store JDBC and disabled true WHEN validated SHOULD pass and warn about plaintext")
    void given_disabled_when_validated_should_pass_and_warn() throws IOException {
        BffProperties properties = bind("encryption-disabled.yml");

        assertThatCode(() -> BffConfiguration.validate(properties)).doesNotThrowAnyException();
        assertThat(BffConfiguration.warnings(properties, new GatewayProperties()))
                .anyMatch(w -> w.contains("encryption.disabled is true") && w.contains("plaintext"));
    }

    @Test
    @DisplayName("GIVEN a key that is not 32 bytes WHEN validated SHOULD name the key id but not its value")
    void given_short_key_when_validated_should_fail_naming_kid() throws IOException {
        assertThat(problem("encryption-bad-length.yml"))
                .contains("session.encryption.keys.k1 must be Base64 of exactly 32 bytes")
                .doesNotContain("c2hvcnQ=");
    }

    @Test
    @DisplayName("GIVEN an active id that is not a key WHEN validated SHOULD fail")
    void given_active_not_in_keys_when_validated_should_fail() throws IOException {
        assertThat(problem("encryption-active-unknown.yml"))
                .contains("session.encryption.active 'k2' is not one of session.encryption.keys");
    }

    @Test
    @DisplayName("GIVEN a key id with a dot WHEN validated SHOULD fail")
    void given_dotted_kid_when_validated_should_fail() throws IOException {
        assertThat(problem("encryption-bad-kid.yml")).contains("key id 'k.1' must match [A-Za-z0-9_-]{1,32}");
    }

    @Test
    @DisplayName("GIVEN two keys and an active one WHEN validated SHOULD pass without warnings")
    void given_valid_keys_when_validated_should_pass() throws IOException {
        BffProperties properties = bind("encryption-valid.yml");

        assertThatCode(() -> BffConfiguration.validate(properties)).doesNotThrowAnyException();
        assertThat(BffConfiguration.warnings(properties, new GatewayProperties()))
                .noneMatch(w -> w.contains("encryption"));
    }

    @Test
    @DisplayName("GIVEN the memory store and no keys WHEN validated SHOULD not ask for any")
    void given_memory_store_when_validated_should_not_require_keys() {
        BffProperties properties = new BffProperties();

        assertThat(BffTokenCipher.problems(properties.getSession())).isEmpty();
        assertThat(BffTokenCipher.from(properties.getSession())).isNull();
    }

    @Test
    @DisplayName("GIVEN properties with keys WHEN printed SHOULD show ids and never key material")
    void given_keys_when_printed_should_mask_them() throws IOException {
        BffProperties properties = bind("encryption-valid.yml");

        assertThat(properties.getSession().toString())
                .contains("k1", "k2")
                .doesNotContain("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }
}
