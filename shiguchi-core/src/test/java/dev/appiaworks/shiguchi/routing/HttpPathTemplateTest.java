package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The google.api.http template grammar: which paths a template matches, what each variable binds,
 * how values are percent-decoded, and the Spring and OpenAPI forms derived from it.
 */
@DisplayName("HttpPathTemplate")
class HttpPathTemplateTest {

    @ParameterizedTest(name = "{0} <- {1}")
    @CsvFileSource(resources = "/path-templates/matches.csv", numLinesToSkip = 1, delimiter = '|')
    @DisplayName("GIVEN a template and a request path WHEN matched SHOULD bind the expected variables")
    void given_templateAndPath_when_matched_should_bindVariables(String template, String path, String expected) {
        Map<String, String> vars = HttpPathTemplate.parse(template).match(path);

        assertThat(vars).isNotNull();
        assertThat(vars.toString()).isEqualTo(expected);
        assertThat(HttpPathTemplate.parse(template).matches(path)).isTrue();
    }

    @ParameterizedTest(name = "{0} <- {1}")
    @CsvFileSource(resources = "/path-templates/non-matches.csv", numLinesToSkip = 1, delimiter = '|')
    @DisplayName("GIVEN a template and a path it does not describe WHEN matched SHOULD not match")
    void given_templateAndForeignPath_when_matched_should_notMatch(String template, String path) {
        assertThat(HttpPathTemplate.parse(template).match(path)).isNull();
        assertThat(HttpPathTemplate.parse(template).matches(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/v1/{name=**}/x",
                "/v1/{}",
                "/v1/{id",
                "/v1/{a b}",
                "/v1/{id}/{id}",
                "/v1/{name=}",
                "/v1//x",
                "/v1/{a={b}}",
                "/v1/x:",
            })
    @DisplayName("GIVEN a template that breaks the grammar WHEN parsed SHOULD be rejected naming the template")
    void given_malformedTemplate_when_parsed_should_throw(String template) {
        assertThatThrownBy(() -> HttpPathTemplate.parse(template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(template);
    }

    @Test
    @DisplayName("GIVEN a multi-segment variable WHEN converted for Spring SHOULD become plain wildcards")
    void given_resourceNameTemplate_when_toPathPattern_should_useWildcards() {
        assertThat(HttpPathTemplate.parse("/v1/{name=shelves/*/books/*}:publish")
                        .toPathPattern())
                .isEqualTo("/v1/shelves/*/books/*:publish");
        assertThat(HttpPathTemplate.parse("/v1/{book.id}").toPathPattern()).isEqualTo("/v1/*");
        assertThat(HttpPathTemplate.parse("/files/{*path}").toPathPattern()).isEqualTo("/files/**");
    }

    @Test
    @DisplayName(
            "GIVEN a deep wildcard followed by a verb WHEN converted for Spring SHOULD widen to the rest of the path")
    void given_deepWildcardAndVerb_when_toPathPattern_should_widen() {
        assertThat(HttpPathTemplate.parse("/v1/{name=**}:undelete").toPathPattern())
                .isEqualTo("/v1/**");
    }

    @Test
    @DisplayName("GIVEN variables with patterns WHEN converted for OpenAPI SHOULD keep only their names")
    void given_resourceNameTemplate_when_toOpenApiPath_should_nameVariablesOnly() {
        HttpPathTemplate template = HttpPathTemplate.parse("/v1/{parent=shelves/*}/books/{book.id}:publish");

        assertThat(template.toOpenApiPath()).isEqualTo("/v1/{parent}/books/{book.id}:publish");
        assertThat(template.variableNames()).containsExactly("parent", "book.id");
    }

    @Test
    @DisplayName("GIVEN the same template twice WHEN looked up through the cache SHOULD return one instance")
    void given_sameTemplate_when_of_should_cache() {
        assertThat(HttpPathTemplate.of("/v1/{id}")).isSameAs(HttpPathTemplate.of("/v1/{id}"));
    }

    @Test
    @DisplayName("GIVEN paths with and without variables WHEN checked SHOULD tell templates from literal paths")
    void given_paths_when_isTemplate_should_detectVariablesAndWildcards() {
        assertThat(HttpPathTemplate.isTemplate("/v1/books:batchGet")).isFalse();
        assertThat(HttpPathTemplate.isTemplate("/v1/{id}")).isTrue();
        assertThat(HttpPathTemplate.isTemplate("/v1/**")).isTrue();
    }
}
