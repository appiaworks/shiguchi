package dev.appiaworks.shiguchi.sample.openfga;

import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Demonstrates per-object authorization at the gateway using OpenFGA.
 *
 * <p>The gateway's built-in check is coarse: it verifies the caller holds the JWT scopes a route
 * declares. This sample replaces the {@link GatewayAuthorizer} bean with one that keeps that scope
 * gate and additionally asks OpenFGA whether the caller may act on the <em>specific object</em> the
 * request names — the question a scope cannot answer.
 *
 * <p>See {@code README.md} in this directory for the authorization model, the tuples to seed, and
 * curl commands demonstrating an allow and a deny.
 */
@SpringBootApplication
@EnableConfigurationProperties(OpenFgaAuthzProperties.class)
public class SampleOpenFgaAuthzApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleOpenFgaAuthzApplication.class, args);
    }

    /**
     * The OpenFGA client. A single instance is shared for the lifetime of the app so that HTTP
     * connections are pooled — the SDK is explicit that it should not be created per request.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sample.openfga", name = "enabled", havingValue = "true")
    public OpenFgaClient openFgaClient(OpenFgaAuthzProperties props) throws FgaInvalidParameterException {
        var config = new ClientConfiguration()
                .apiUrl(props.getApiUrl())
                .storeId(props.getStoreId())
                // Pinning the model id keeps decisions reproducible: without it, OpenFGA evaluates
                // against whatever model is latest, so deploying a new model silently changes the
                // answers this gateway gives.
                .authorizationModelId(props.getAuthorizationModelId())
                .connectTimeout(props.getTimeout())
                .readTimeout(props.getTimeout());
        return new OpenFgaClient(config);
    }

    /**
     * Replaces the gateway's default authorizer. Because the gateway registers its own bean with
     * {@code @ConditionalOnMissingBean(GatewayAuthorizer.class)}, defining this one takes over.
     *
     * <p>{@link ScopeGatewayAuthorizer} is constructed directly and composed in, so the annotation
     * scope check still runs first and this only <em>adds</em> the object-level check.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sample.openfga", name = "enabled", havingValue = "true")
    public GatewayAuthorizer openFgaAuthorizer(OpenFgaClient client, OpenFgaAuthzProperties props) {
        return new OpenFgaGatewayAuthorizer(new ScopeGatewayAuthorizer(), client, props);
    }
}
