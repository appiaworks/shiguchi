package dev.appiaworks.shiguchi.sample.opa;

import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Demonstrates gateway authorization delegated to Open Policy Agent.
 *
 * <p>Companion to {@code sample-openfga-authz}. Both replace the same {@link GatewayAuthorizer}
 * bean, but they exercise the SPI from opposite directions — OpenFGA answers a fixed
 * {@code (user, relation, object)} question, while OPA evaluates arbitrary Rego over a document
 * describing the whole request. Reading the two side by side shows what the SPI does and does not
 * assume about a policy engine.
 *
 * <p>See {@code README.md} in this directory for the Rego policy and curl commands.
 */
@SpringBootApplication
@EnableConfigurationProperties(OpaAuthzProperties.class)
public class SampleOpaAuthzApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleOpaAuthzApplication.class, args);
    }

    /**
     * Replaces the gateway's default authorizer. The gateway registers its own with
     * {@code @ConditionalOnMissingBean(GatewayAuthorizer.class)}, so defining this one takes over.
     *
     * <p>{@link ScopeGatewayAuthorizer} is composed in rather than replaced, so the annotation
     * scope check still runs first and OPA only <em>adds</em> to it.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sample.opa", name = "enabled", havingValue = "true")
    public GatewayAuthorizer opaAuthorizer(WebClient.Builder webClientBuilder, OpaAuthzProperties props) {
        return new OpaGatewayAuthorizer(new ScopeGatewayAuthorizer(), webClientBuilder, props);
    }
}
