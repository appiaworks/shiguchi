package dev.appiaworks.shiguchi.bff.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BffSessionClaims")
class BffSessionClaimsTest {

    private static Map<String, Object> source() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Alice");
        m.put("email", "alice@example.com");
        m.put("roles", List.of("admin", "dev"));
        m.put("tenant", "acme");
        m.put("level", 3L);
        m.put("active", true);
        m.put("team", Map.of("name", "core", "tags", List.of("a", "b")));
        m.put("too_deep", Map.of("a", Map.of("b", Map.of("c", 1))));
        m.put("array_of_arrays", List.of(List.of(List.of(1))));
        m.put("not_json", new Object());
        m.put("unlisted", "x");
        return m;
    }

    @Test
    @DisplayName("GIVEN listed names WHEN selecting SHOULD keep JSON-like values in the listed order")
    void given_listed_names_when_selecting_should_keepValues() {
        Map<String, Object> out =
                BffSessionClaims.select(source(), List.of("tenant", "roles", "level", "active", "team"), "a");

        assertThat(out.keySet()).containsExactly("tenant", "roles", "level", "active", "team");
        assertThat(out)
                .containsEntry("roles", List.of("admin", "dev"))
                .containsEntry("level", 3L)
                .containsEntry("active", true);
    }

    @Test
    @DisplayName("GIVEN name, email and picture listed WHEN selecting SHOULD leave them to their own fields")
    void given_fixedClaims_when_selecting_should_skipThem() {
        assertThat(BffSessionClaims.select(source(), List.of("name", "email", "picture", "tenant"), "a"))
                .containsOnlyKeys("tenant");
    }

    @Test
    @DisplayName("GIVEN claims nested deeper than two levels or not JSON-like WHEN selecting SHOULD drop only those")
    void given_tooDeepOrForeign_when_selecting_should_dropThem() {
        Map<String, Object> out =
                BffSessionClaims.select(source(), List.of("too_deep", "array_of_arrays", "not_json", "tenant"), "a");

        assertThat(out).containsOnlyKeys("tenant");
    }

    @Test
    @DisplayName("GIVEN unlisted or absent claims WHEN selecting SHOULD not return them")
    void given_unlistedOrAbsent_when_selecting_should_skip() {
        assertThat(BffSessionClaims.select(source(), List.of("missing", "tenant"), "a"))
                .containsOnlyKeys("tenant");
        assertThat(BffSessionClaims.select(source(), List.of(), "a")).isEmpty();
    }

    @Test
    @DisplayName("GIVEN claims over 4 KB in total WHEN selecting SHOULD drop the ones that no longer fit")
    void given_oversizedClaims_when_selecting_should_dropTheExtra() {
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("first", "a".repeat(3000));
        big.put("second", "b".repeat(3000));
        big.put("third", "c".repeat(100));

        Map<String, Object> out = BffSessionClaims.select(big, List.of("first", "second", "third"), "a");

        assertThat(out).containsOnlyKeys("first", "third");
        assertThat(BffSessionClaims.toJson(out).length()).isLessThanOrEqualTo(BffSessionClaims.MAX_BYTES);
    }

    @Test
    @DisplayName("GIVEN claims WHEN written to JSON and read back SHOULD be equal")
    void given_claims_when_roundTripping_should_beEqual() {
        Map<String, Object> claims = BffSessionClaims.select(source(), List.of("roles", "team", "tenant"), "a");

        assertThat(BffSessionClaims.fromJson(BffSessionClaims.toJson(claims))).isEqualTo(claims);
    }

    @Test
    @DisplayName("GIVEN null, blank or damaged text WHEN reading SHOULD return no claims and never throw")
    void given_damagedText_when_reading_should_returnEmpty() {
        assertThat(BffSessionClaims.fromJson(null)).isEmpty();
        assertThat(BffSessionClaims.fromJson("  ")).isEmpty();
        assertThat(BffSessionClaims.fromJson("{not json")).isEmpty();
        assertThat(BffSessionClaims.fromJson("[1,2]")).isEmpty();
        assertThat(BffSessionClaims.toJson(Map.of())).isNull();
    }
}
