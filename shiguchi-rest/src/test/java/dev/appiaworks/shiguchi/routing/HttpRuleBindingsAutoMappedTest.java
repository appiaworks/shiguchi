package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.HttpRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for {@link HttpRuleBindings#autoMapped(String, String)}: the fallback binding
 * {@code rest.auto-mapping} creates for methods without a {@code google.api.http} rule.
 *
 * <p>The auto-mapped binding uses the gRPC method path ({@code /package.Service/Method}) to
 * avoid collision with any annotated route, and always accepts {@code "*"} (the whole message)
 * as the body.
 */
@DisplayName("REST auto-mapping bindings for unannotated methods")
class HttpRuleBindingsAutoMappedTest {

    @Test
    @DisplayName(
            "GIVEN no http rule WHEN autoMapped is called SHOULD return POST to the gRPC path with whole-message body")
    void given_noRule_when_autoMapped_should_returnPostToGrpcPath() {
        HttpRuleBindings.Binding binding = HttpRuleBindings.autoMapped("acme.v1.Books", "GetBook");

        assertThat(binding.method()).isEqualTo("POST");
        assertThat(binding.path()).isEqualTo("/acme.v1.Books/GetBook");
        assertThat(binding.body()).isEqualTo("*");
        assertThat(binding.responseBody()).isNull();
    }

    @Test
    @DisplayName("GIVEN another service and method WHEN autoMapped is called SHOULD use their exact names in the path")
    void given_differentServiceAndMethod_when_autoMapped_should_useTheirNames() {
        HttpRuleBindings.Binding binding = HttpRuleBindings.autoMapped("example.v2.Orders", "CreateOrder");

        assertThat(binding.path()).isEqualTo("/example.v2.Orders/CreateOrder");
    }

    @Test
    @DisplayName("GIVEN a default empty HttpRule WHEN converted with of() SHOULD return empty bindings")
    void given_emptyHttpRule_when_of_should_returnEmpty() {
        HttpRule emptyRule = HttpRule.getDefaultInstance();
        List<HttpRuleBindings.Binding> bindings = HttpRuleBindings.of(emptyRule);

        assertThat(bindings).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a null HttpRule WHEN converted with of() SHOULD return empty bindings")
    void given_nullHttpRule_when_of_should_returnEmpty() {
        List<HttpRuleBindings.Binding> bindings = HttpRuleBindings.of(null);

        assertThat(bindings).isEmpty();
    }
}
