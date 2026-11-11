package dev.appiaworks.shiguchi.docs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps docs/reference/configuration.md honest for {@link GatewayProperties}. The roots bound by
 * other modules (bff, cursus, validation) are checked by drift tests in those modules, on the same
 * {@link ConfigurationReferenceChecker}.
 */
class ConfigurationReferenceDriftTest {

    private static final ConfigurationReferenceChecker CHECKER =
            new ConfigurationReferenceChecker(GatewayProperties.class, "shiguchi");

    /** Roots bound by other modules: documented here, checked by those modules' own types. */
    private static final Set<String> OTHER_MODULES = Set.of("shiguchi.bff", "shiguchi.cursus", "shiguchi.validation");

    @Test
    @DisplayName("GIVEN the bound properties WHEN reading the reference SHOULD name every one of them")
    void GIVEN_bound_properties_WHEN_reading_reference_SHOULD_name_every_one() throws IOException {
        assertThat(CHECKER.undocumented())
                .as("bound properties that docs/reference/configuration.md never names next to their parent")
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN the reference WHEN reading its full property names SHOULD find each one in the code")
    void GIVEN_reference_WHEN_reading_full_names_SHOULD_find_each_in_code() throws IOException {
        assertThat(CHECKER.unknownDocumented(Set.of("shiguchi")).stream()
                        .filter(n -> OTHER_MODULES.stream().noneMatch(o -> n.equals(o) || n.startsWith(o + ".")))
                        .toList())
                .as("documented properties the code does not bind")
                .isEmpty();
    }
}
