package dev.appiaworks.shiguchi.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How backend versions published in registry metadata are ordered. */
@DisplayName("Version order")
class VersionOrderTest {

    private static List<String> sorted(String... versions) {
        List<String> list = new ArrayList<>(List.of(versions));
        list.sort(VersionOrder.INSTANCE);
        return list;
    }

    @Test
    @DisplayName("GIVEN multi-digit parts WHEN sorted SHOULD compare numerically, not as text")
    void given_multiDigitParts_when_sorted_should_compareNumerically() {
        assertThat(sorted("1.10.0", "1.9.2", "1.2.0", "10.0.0")).containsExactly("1.2.0", "1.9.2", "1.10.0", "10.0.0");
    }

    @Test
    @DisplayName("GIVEN a pre-release and its release WHEN sorted SHOULD put the pre-release first")
    void given_preRelease_when_sorted_should_precedeRelease() {
        assertThat(sorted("2.0.0", "2.0.0-rc.2", "2.0.0-rc.10", "1.9.0"))
                .containsExactly("1.9.0", "2.0.0-rc.2", "2.0.0-rc.10", "2.0.0");
    }

    @Test
    @DisplayName("GIVEN a v prefix or missing trailing zeros WHEN compared SHOULD treat them as the same version")
    void given_prefixOrShortForm_when_compared_should_beEqual() {
        assertThat(VersionOrder.INSTANCE.compare("v1.2", "1.2.0")).isZero();
        assertThat(VersionOrder.INSTANCE.compare("V3", "3.0")).isZero();
        assertThat(VersionOrder.INSTANCE.compare("1.02", "1.2")).isZero();
    }

    @Test
    @DisplayName("GIVEN an instance without a version WHEN sorted SHOULD come before every versioned one")
    void given_missingVersion_when_sorted_should_beOldest() {
        assertThat(sorted("0.0.1", "", "1.0")).containsExactly("", "0.0.1", "1.0");
        assertThat(VersionOrder.INSTANCE.compare(null, "")).isZero();
    }

    @Test
    @DisplayName("GIVEN date-like and textual versions WHEN sorted SHOULD order numbers before words, each in order")
    void given_mixedVersions_when_sorted_should_beStable() {
        assertThat(sorted("2026.09.30", "2026.10.01", "2026.9.29"))
                .containsExactly("2026.9.29", "2026.09.30", "2026.10.01");
        assertThat(sorted("1.alpha", "1.2", "1.beta")).containsExactly("1.2", "1.alpha", "1.beta");
    }
}
