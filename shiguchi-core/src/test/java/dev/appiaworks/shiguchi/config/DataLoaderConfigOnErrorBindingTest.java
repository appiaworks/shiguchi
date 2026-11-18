package dev.appiaworks.shiguchi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

class DataLoaderConfigOnErrorBindingTest {

    private static DataLoaderConfig bind(String yaml) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("test", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        MutablePropertySources all = new MutablePropertySources();
        sources.forEach(all::addLast);
        return new Binder(ConfigurationPropertySources.from(all))
                .bind("shiguchi.graphql.data-loader", DataLoaderConfig.class)
                .orElseGet(DataLoaderConfig::new);
    }

    @Test
    @DisplayName("GIVEN_noOnError_WHEN_bound_SHOULD_defaultToError")
    void GIVEN_noOnError_WHEN_bound_SHOULD_defaultToError() throws Exception {
        assertThat(bind("shiguchi.graphql.data-loader.timeout: 5s\n").getOnError())
                .isEqualTo(DataLoaderConfig.OnError.ERROR);
    }

    @Test
    @DisplayName("GIVEN_quotedNullInYaml_WHEN_bound_SHOULD_selectNullMode")
    void GIVEN_quotedNullInYaml_WHEN_bound_SHOULD_selectNullMode() throws Exception {
        assertThat(bind("shiguchi.graphql.data-loader.on-error: \"NULL\"\n").getOnError())
                .isEqualTo(DataLoaderConfig.OnError.NULL);
    }

    @Test
    @DisplayName("GIVEN_unquotedNullInYaml_WHEN_bound_SHOULD_leaveTheDefaultBecauseYamlReadsItAsNull")
    void GIVEN_unquotedNullInYaml_WHEN_bound_SHOULD_leaveTheDefaultBecauseYamlReadsItAsNull() throws Exception {
        // YAML 1.1 turns a bare NULL into a null value, so the key is unset: the quoting note in the docs.
        assertThat(bind("shiguchi.graphql.data-loader.on-error: NULL\n").getOnError())
                .isEqualTo(DataLoaderConfig.OnError.ERROR);
    }
}
