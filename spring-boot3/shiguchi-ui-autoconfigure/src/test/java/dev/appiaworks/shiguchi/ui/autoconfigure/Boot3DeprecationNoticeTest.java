package dev.appiaworks.shiguchi.ui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.util.StringUtils;

/**
 * Behavioral tests for {@link Boot3DeprecationNotice}.
 *
 * <p>The once-per-JVM guard is a system property, so every test clears it before and after running
 * to keep the tests independent of each other and of other test classes in the same JVM.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Boot3DeprecationNotice")
class Boot3DeprecationNoticeTest {

    @BeforeEach
    void clearLoggedFlag() {
        System.clearProperty(Boot3DeprecationNotice.LOGGED_PROPERTY);
    }

    @AfterEach
    void restoreLoggedFlag() {
        System.clearProperty(Boot3DeprecationNotice.LOGGED_PROPERTY);
    }

    @Test
    @DisplayName("GIVEN notice not yet logged WHEN logOnce SHOULD warn with migration message")
    void GIVEN_notice_not_yet_logged_WHEN_logOnce_SHOULD_warn_with_migration_message(CapturedOutput output) {
        // Arrange
        // (no notice has been logged in this JVM: the flag is cleared)

        // Act
        Boot3DeprecationNotice.logOnce();

        // Assert
        assertThat(output.getAll())
                .contains("Spring Boot 3 starters are deprecated")
                .contains("WARN");
    }

    @Test
    @DisplayName("GIVEN notice already logged WHEN logOnce is called again SHOULD not repeat the message")
    void GIVEN_notice_already_logged_WHEN_logOnce_called_again_SHOULD_not_repeat(CapturedOutput output) {
        // Arrange
        Boot3DeprecationNotice.logOnce();

        // Act
        Boot3DeprecationNotice.logOnce();

        // Assert
        assertThat(StringUtils.countOccurrencesOf(output.getAll(), Boot3DeprecationNotice.MESSAGE))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("GIVEN another module already logged WHEN logOnce SHOULD stay silent")
    void GIVEN_another_module_already_logged_WHEN_logOnce_SHOULD_stay_silent(CapturedOutput output) {
        // Arrange
        System.setProperty(Boot3DeprecationNotice.LOGGED_PROPERTY, "true");

        // Act
        Boot3DeprecationNotice.logOnce();

        // Assert
        assertThat(output.getAll()).doesNotContain(Boot3DeprecationNotice.MESSAGE);
    }
}
