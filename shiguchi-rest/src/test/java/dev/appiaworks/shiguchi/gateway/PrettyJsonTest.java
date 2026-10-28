package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PrettyJson")
class PrettyJsonTest {

    private static String indent(String json) {
        return new String(PrettyJson.indent(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("GIVEN a compact object WHEN indented SHOULD put each field on its own line in the original order")
    void given_compactObject_when_indented_should_breakLinesKeepingOrder() {
        String pretty = indent("{\"b\":1,\"a\":[true,null],\"c\":{\"d\":\"x\"}}");

        assertThat(pretty.lines()).hasSizeGreaterThan(5);
        assertThat(pretty.indexOf("\"b\"")).isLessThan(pretty.indexOf("\"a\""));
        assertThat(pretty.replaceAll("\\s", "")).isEqualTo("{\"b\":1,\"a\":[true,null],\"c\":{\"d\":\"x\"}}");
    }

    @Test
    @DisplayName("GIVEN numbers beyond double precision WHEN indented SHOULD copy them exactly as written")
    void given_preciseNumbers_when_indented_should_keepThemExact() {
        String pretty = indent("{\"big\":9223372036854775807,\"dec\":0.1000000000000000055511151231257827}");

        assertThat(pretty).contains("9223372036854775807").contains("0.1000000000000000055511151231257827");
    }

    @Test
    @DisplayName("GIVEN a top-level array or an empty body WHEN indented SHOULD handle both")
    void given_arrayOrEmpty_when_indented_should_handleBoth() {
        assertThat(indent("[1,2]").replaceAll("\\s", "")).isEqualTo("[1,2]");
        assertThat(PrettyJson.indent(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("GIVEN bytes that are not JSON WHEN indented SHOULD fail rather than send garbage")
    void given_notJson_when_indented_should_fail() {
        assertThatThrownBy(() -> indent("{not json")).isInstanceOf(UncheckedIOException.class);
    }
}
