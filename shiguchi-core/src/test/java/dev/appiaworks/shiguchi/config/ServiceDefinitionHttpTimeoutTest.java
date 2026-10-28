package dev.appiaworks.shiguchi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceDefinition.effectiveHttpTimeout")
class ServiceDefinitionHttpTimeoutTest {

    private static ClientConfig client(Duration defaultDeadline, Duration httpTimeout) {
        var c = new ClientConfig();
        c.setDefaultDeadline(defaultDeadline);
        c.setHttpTimeout(httpTimeout);
        return c;
    }

    @Test
    @DisplayName("GIVEN nothing configured WHEN resolved SHOULD be the 30s client.http-timeout default")
    void given_defaults_when_resolved_should_beThirtySeconds() {
        assertThat(new ServiceDefinition().effectiveHttpTimeout("getItem", new ClientConfig()))
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("GIVEN a custom client.http-timeout WHEN resolved SHOULD use it")
    void given_httpTimeout_when_resolved_should_useIt() {
        assertThat(new ServiceDefinition()
                        .effectiveHttpTimeout("getItem", client(Duration.ZERO, Duration.ofSeconds(7))))
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("GIVEN a positive client.default-deadline WHEN resolved SHOULD win over http-timeout")
    void given_defaultDeadline_when_resolved_should_winOverHttpTimeout() {
        assertThat(new ServiceDefinition()
                        .effectiveHttpTimeout("getItem", client(Duration.ofSeconds(5), Duration.ofSeconds(7))))
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN a positive service deadline WHEN resolved SHOULD win over default-deadline")
    void given_serviceDeadline_when_resolved_should_winOverDefaultDeadline() {
        var svc = new ServiceDefinition();
        svc.setDeadline(Duration.ofSeconds(3));

        assertThat(svc.effectiveHttpTimeout("getItem", client(Duration.ofSeconds(5), Duration.ofSeconds(7))))
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("GIVEN an operation timeout WHEN resolved SHOULD win over every other level")
    void given_operationTimeout_when_resolved_should_winOverEverything() {
        var svc = new ServiceDefinition();
        svc.setDeadline(Duration.ofSeconds(3));
        svc.getOperationTimeouts().put("getItem", Duration.ofMillis(250));

        assertThat(svc.effectiveHttpTimeout("getItem", client(Duration.ofSeconds(5), Duration.ofSeconds(7))))
                .isEqualTo(Duration.ofMillis(250));
        assertThat(svc.effectiveHttpTimeout("other", client(Duration.ofSeconds(5), Duration.ofSeconds(7))))
                .isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("GIVEN a zero service deadline and default-deadline WHEN resolved SHOULD fall back to http-timeout")
    void given_zeroDeadlines_when_resolved_should_fallBackToHttpTimeout() {
        var svc = new ServiceDefinition();
        svc.setDeadline(Duration.ZERO);

        assertThat(svc.effectiveHttpTimeout("getItem", client(Duration.ZERO, Duration.ofSeconds(7))))
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("GIVEN a negative service deadline WHEN resolved SHOULD not disable the timeout")
    void given_negativeDeadline_when_resolved_should_notDisableTimeout() {
        var svc = new ServiceDefinition();
        svc.setDeadline(Duration.ofSeconds(-1));

        assertThat(svc.effectiveHttpTimeout(null, client(Duration.ofSeconds(-2), Duration.ofSeconds(9))))
                .isEqualTo(Duration.ofSeconds(9));
    }
}
