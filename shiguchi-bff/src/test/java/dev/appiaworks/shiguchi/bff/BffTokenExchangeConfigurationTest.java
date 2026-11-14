package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BffTokenExchanger.problems: startup validation of provider.exchange")
class BffTokenExchangeConfigurationTest {

    private static BffProperties.ExchangeTarget target(String audience, String scope) {
        BffProperties.ExchangeTarget target = new BffProperties.ExchangeTarget();
        target.setAudience(audience);
        target.setScope(scope);
        return target;
    }

    private static BffProperties.Provider exchanging() {
        BffProperties.Provider provider = new BffProperties.Provider();
        provider.setUpstreamTokenMode(BffProperties.UpstreamTokenMode.EXCHANGE);
        return provider;
    }

    @Test
    @DisplayName("GIVEN RELAY mode with no exchange block WHEN validating SHOULD report nothing")
    void given_relay_when_validating_should_report_nothing() {
        assertThat(BffTokenExchanger.problems("idp", new BffProperties.Provider()))
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN EXCHANGE with no target and no default WHEN validating SHOULD fail with a clear message")
    void given_exchange_without_target_when_validating_should_fail_clearly() {
        List<String> problems = BffTokenExchanger.problems("idp", exchanging());

        assertThat(problems)
                .singleElement()
                .asString()
                .contains("provider 'idp'")
                .contains("EXCHANGE")
                .contains("exchange.targets")
                .contains("exchange.default-target");
    }

    @Test
    @DisplayName("GIVEN EXCHANGE with only a default target WHEN validating SHOULD accept")
    void given_exchange_with_default_when_validating_should_accept() {
        BffProperties.Provider provider = exchanging();
        provider.getExchange().setDefaultTarget(target("api", null));

        assertThat(BffTokenExchanger.problems("idp", provider)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN EXCHANGE with only a mapped service WHEN validating SHOULD accept")
    void given_exchange_with_target_when_validating_should_accept() {
        BffProperties.Provider provider = exchanging();
        provider.getExchange().getTargets().put("acme.Svc", target(null, "read"));

        assertThat(BffTokenExchanger.problems("idp", provider)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a target with neither audience nor scope WHEN validating SHOULD report it")
    void given_empty_target_when_validating_should_report_it() {
        BffProperties.Provider provider = exchanging();
        provider.getExchange().getTargets().put("acme.Svc", target(" ", null));

        assertThat(BffTokenExchanger.problems("idp", provider))
                .singleElement()
                .asString()
                .contains("exchange.targets.acme.Svc")
                .contains("audience or a scope");
    }

    @Test
    @DisplayName("GIVEN extra parameters naming a protocol parameter WHEN validating SHOULD report it")
    void given_protocol_extra_parameter_when_validating_should_report_it() {
        BffProperties.Provider provider = exchanging();
        provider.getExchange().setDefaultTarget(target("api", null));
        provider.getExchange().setExtraParameters(Map.of("subject_token", "x", "resource", "y"));

        assertThat(BffTokenExchanger.problems("idp", provider))
                .singleElement()
                .asString()
                .contains("subject_token");
    }

    @Test
    @DisplayName("GIVEN RELAY mode with an exchange block WHEN validating SHOULD ignore the block")
    void given_relay_with_exchange_block_when_validating_should_ignore_it() {
        BffProperties.Provider provider = new BffProperties.Provider();
        provider.getExchange().getTargets().put("acme.Svc", target(null, null));

        assertThat(BffTokenExchanger.problems("idp", provider)).isEmpty();
    }
}
