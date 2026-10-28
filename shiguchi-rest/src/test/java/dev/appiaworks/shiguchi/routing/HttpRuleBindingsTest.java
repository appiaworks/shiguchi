package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.CustomHttpPattern;
import com.google.api.HttpRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HttpRuleBindings: google.api.http rule → REST bindings")
class HttpRuleBindingsTest {

    @Test
    @DisplayName("GIVEN a rule with only a get pattern WHEN read SHOULD yield one GET binding with no body")
    void given_getRule_when_read_should_yieldGetWithoutBody() {
        List<HttpRuleBindings.Binding> b = HttpRuleBindings.of(
                HttpRule.newBuilder().setGet("/v1/books/{id}").build());

        assertThat(b).containsExactly(new HttpRuleBindings.Binding("GET", "/v1/books/{id}", null, null));
    }

    @Test
    @DisplayName("GIVEN a post rule with body WHEN read SHOULD carry the body field name")
    void given_postRuleWithBody_when_read_should_carryBodyField() {
        List<HttpRuleBindings.Binding> b = HttpRuleBindings.of(
                HttpRule.newBuilder().setPost("/v1/books").setBody("book").build());

        assertThat(b).containsExactly(new HttpRuleBindings.Binding("POST", "/v1/books", "book", null));
    }

    @Test
    @DisplayName("GIVEN additional_bindings WHEN read SHOULD list the primary first then each extra in order")
    void given_additionalBindings_when_read_should_listPrimaryThenExtrasInOrder() {
        HttpRule rule = HttpRule.newBuilder()
                .setGet("/v1/books/{id}")
                .addAdditionalBindings(HttpRule.newBuilder().setGet("/v1/shelves/{shelf}/books/{id}"))
                .addAdditionalBindings(
                        HttpRule.newBuilder().setPost("/v1/books:lookup").setBody("*"))
                .build();

        assertThat(HttpRuleBindings.of(rule))
                .containsExactly(
                        new HttpRuleBindings.Binding("GET", "/v1/books/{id}", null, null),
                        new HttpRuleBindings.Binding("GET", "/v1/shelves/{shelf}/books/{id}", null, null),
                        new HttpRuleBindings.Binding("POST", "/v1/books:lookup", "*", null));
    }

    @Test
    @DisplayName("GIVEN a custom pattern WHEN read SHOULD use its kind as the HTTP method, upper-cased")
    void given_customPattern_when_read_should_useKindAsMethod() {
        HttpRule rule = HttpRule.newBuilder()
                .setCustom(CustomHttpPattern.newBuilder().setKind("head").setPath("/v1/books/{id}"))
                .build();

        assertThat(HttpRuleBindings.of(rule))
                .containsExactly(new HttpRuleBindings.Binding("HEAD", "/v1/books/{id}", null, null));
    }

    @Test
    @DisplayName("GIVEN a rule with no pattern (the default instance) WHEN read SHOULD yield nothing")
    void given_noPattern_when_read_should_yieldNothing() {
        assertThat(HttpRuleBindings.of(HttpRule.getDefaultInstance())).isEmpty();
        assertThat(HttpRuleBindings.of(null)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an additional binding without a pattern WHEN read SHOULD skip just that entry")
    void given_patternlessExtra_when_read_should_skipIt() {
        HttpRule rule = HttpRule.newBuilder()
                .setDelete("/v1/books/{id}")
                .addAdditionalBindings(HttpRule.newBuilder().setBody("*"))
                .addAdditionalBindings(
                        HttpRule.newBuilder().setPatch("/v1/books/{id}").setBody("book"))
                .build();

        assertThat(HttpRuleBindings.of(rule))
                .extracting(HttpRuleBindings.Binding::method)
                .containsExactly("DELETE", "PATCH");
    }
}
