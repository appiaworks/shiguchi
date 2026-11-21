package dev.appiaworks.shiguchi.validation.autoconfigure;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Satisfied when at least one of gateway-enabled or server-enabled is true.
 * Used to create the shared {@link dev.appiaworks.shiguchi.validation.ProtoValidator} bean
 * only when something actually needs it.
 */
class OnValidationEnabledCondition extends AnyNestedCondition {

    OnValidationEnabledCondition() {
        super(ConfigurationPhase.REGISTER_BEAN);
    }

    @ConditionalOnProperty(prefix = "shiguchi.validation", name = "gateway-enabled", havingValue = "true")
    static class GatewayEnabled {}

    @ConditionalOnProperty(prefix = "shiguchi.validation", name = "server-enabled", havingValue = "true")
    static class ServerEnabled {}
}
