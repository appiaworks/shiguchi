package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.ReloadSchemaIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for {@link GatewaySchemaReloadTrigger}.
 *
 * <p>The trigger is a {@code BeanPostProcessor}: the tests drive it through
 * {@code postProcessAfterInitialization}, the way Spring applies it to the DGS indicator bean,
 * and then observe only the wrapped indicator's answers.
 */
@DisplayName("GatewaySchemaReloadTrigger")
class GatewaySchemaReloadTriggerTest {

    @Test
    @DisplayName(
            "GIVEN a DGS reload indicator wrapped by the trigger WHEN requestReload is called SHOULD answer true exactly once")
    void given_wrappedIndicator_when_requestReloadCalled_should_answerTrueExactlyOnce() {
        // Arrange
        GatewaySchemaReloadTrigger trigger = new GatewaySchemaReloadTrigger();
        ReloadSchemaIndicator indicator = (ReloadSchemaIndicator)
                trigger.postProcessAfterInitialization((ReloadSchemaIndicator) () -> false, "dgsIndicator");
        trigger.requestReload();

        // Act
        boolean firstAnswer = indicator.reloadSchema();
        boolean secondAnswer = indicator.reloadSchema();

        // Assert
        assertThat(firstAnswer).isTrue();
        assertThat(secondAnswer).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN an original indicator that answers true and no reload requested WHEN the wrapped indicator is asked SHOULD still answer true")
    void given_originalIndicatorAnswersTrue_when_wrappedIndicatorAsked_should_stillAnswerTrue() {
        // Arrange
        GatewaySchemaReloadTrigger trigger = new GatewaySchemaReloadTrigger();
        ReloadSchemaIndicator indicator = (ReloadSchemaIndicator)
                trigger.postProcessAfterInitialization((ReloadSchemaIndicator) () -> true, "dgsIndicator");

        // Act
        boolean answer = indicator.reloadSchema();

        // Assert
        assertThat(answer).isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN a bean that is not a reload indicator WHEN the trigger post-processes it SHOULD return the same bean unchanged")
    void given_nonIndicatorBean_when_postProcessed_should_returnSameBean() {
        // Arrange
        GatewaySchemaReloadTrigger trigger = new GatewaySchemaReloadTrigger();
        Object bean = new Object();

        // Act
        Object result = trigger.postProcessAfterInitialization(bean, "someBean");

        // Assert
        assertThat(result).isSameAs(bean);
    }

    @Test
    @DisplayName("GIVEN a wrapped indicator that answers false and no reload requested WHEN asked SHOULD answer false")
    void given_noReloadRequested_when_wrappedIndicatorAsked_should_answerFalse() {
        // Arrange
        GatewaySchemaReloadTrigger trigger = new GatewaySchemaReloadTrigger();
        ReloadSchemaIndicator indicator = (ReloadSchemaIndicator)
                trigger.postProcessAfterInitialization((ReloadSchemaIndicator) () -> false, "dgsIndicator");

        // Act
        boolean answer = indicator.reloadSchema();

        // Assert
        assertThat(answer).isFalse();
    }
}
