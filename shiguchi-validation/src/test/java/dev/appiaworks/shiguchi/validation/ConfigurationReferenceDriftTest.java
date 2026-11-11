package dev.appiaworks.shiguchi.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.docs.ConfigurationReferenceChecker;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Keeps the {@code shiguchi.validation} rows of docs/reference/configuration.md in step with {@link ValidationProperties}. */
class ConfigurationReferenceDriftTest {

    private static final ConfigurationReferenceChecker CHECKER =
            new ConfigurationReferenceChecker(ValidationProperties.class, "shiguchi.validation");

    @Test
    @DisplayName("GIVEN the validation properties WHEN reading the reference SHOULD name every one of them")
    void GIVEN_validation_properties_WHEN_reading_reference_SHOULD_name_every_one() throws IOException {
        assertThat(CHECKER.undocumented())
                .as(
                        "bound shiguchi.validation properties that docs/reference/configuration.md never names next to their parent")
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN the reference WHEN reading its shiguchi.validation names SHOULD find each one in the code")
    void GIVEN_reference_WHEN_reading_validation_names_SHOULD_find_each_in_code() throws IOException {
        assertThat(CHECKER.unknownDocumented(Set.of("shiguchi.validation")))
                .as("documented shiguchi.validation properties the code does not bind")
                .isEmpty();
    }
}
