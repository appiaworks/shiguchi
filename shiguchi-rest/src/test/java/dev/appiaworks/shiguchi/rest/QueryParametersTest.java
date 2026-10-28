package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QueryParameters")
class QueryParametersTest {

    @Test
    @DisplayName("GIVEN a repeated name WHEN built SHOULD map the first value and keep all of them in order")
    void given_repeatedName_when_built_should_keepFirstAndAll() {
        QueryParameters params = QueryParameters.of(Map.of("tag", List.of("a", "b", "c")));

        assertThat(params).containsEntry("tag", "a");
        assertThat(QueryParameters.all(params, "tag")).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("GIVEN a name with no values WHEN built SHOULD leave it out")
    void given_nameWithoutValues_when_built_should_skipIt() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("empty", List.of());
        raw.put("flag", Arrays.asList((String) null));

        QueryParameters params = QueryParameters.of(raw);

        assertThat(params).doesNotContainKey("empty").containsEntry("flag", null);
        assertThat(QueryParameters.all(params, "flag")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a plain map WHEN all values are asked for SHOULD answer its single value")
    void given_plainMap_when_all_should_answerSingleValue() {
        assertThat(QueryParameters.all(Map.of("tag", "a"), "tag")).containsExactly("a");
        assertThat(QueryParameters.all(Map.of(), "tag")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an entry replaced after construction WHEN all values are asked for SHOULD answer the new value")
    void given_replacedEntry_when_all_should_answerReplacement() {
        QueryParameters params = QueryParameters.of(Map.of("fields", List.of("a", "b")));
        params.put("fields", "title");

        assertThat(QueryParameters.all(params, "fields")).containsExactly("title");
    }
}
