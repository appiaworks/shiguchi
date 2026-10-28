package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.protobuf.extras.ProtoJson;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.shiguchi.authz.GatewayAuthorizer;
import dev.appiaworks.shiguchi.authz.ScopeGatewayAuthorizer;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GatewayPropertiesValidator;
import dev.appiaworks.shiguchi.rest.DefaultGrpcStatusMapper;
import dev.appiaworks.shiguchi.rest.GrpcStatusMapper;
import dev.appiaworks.shiguchi.serde.GatewayJsonSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.validation.Validator;

/**
 * The gateway's overridable defaults, {@code @Import}ed by every auto-configuration that
 * component-scans {@code dev.appiaworks.shiguchi} — the gateway's own and the UI starter's, on both Boot
 * lines — so a starter used on its own has them too. Deliberately not a {@code @Configuration}
 * (which is a {@code @Component}): the scan must not pick this up, or the defaults would be
 * registered by the scan before a host application's overriding beans and the
 * {@code @ConditionalOnMissingBean} back-off would stop working. As an import of an
 * auto-configuration it is processed after user configuration, which is what makes the back-off
 * reliable. Define your own bean of any of these types and the default disappears.
 */
public class GatewayDefaults {

    /**
     * Validates {@code shiguchi.*} while Spring Boot binds it, so a missing address, an
     * out-of-range port or a {@code PB_FILE} service without a descriptor path stops the
     * application with the property path and the reason (Boot's {@code
     * BindValidationFailureAnalyzer}) instead of failing later inside a route loader. Boot looks
     * this bean up by name; static so it exists before any properties are bound. Define your own
     * {@code configurationPropertiesValidator} and this one backs off — delegate to
     * {@link GatewayPropertiesValidator#validate(GatewayProperties)} from it to keep the checks.
     */
    @Bean
    @ConditionalOnMissingBean(name = "configurationPropertiesValidator")
    public static Validator configurationPropertiesValidator() {
        return new GatewayPropertiesValidator();
    }

    /** gRPC→HTTP status mapping; replace with your own {@link GrpcStatusMapper} bean. */
    @Bean
    @ConditionalOnMissingBean(GrpcStatusMapper.class)
    public GrpcStatusMapper grpcStatusMapper() {
        return new DefaultGrpcStatusMapper();
    }

    /** Error body shape; replace with your own {@link GatewayErrorRenderer} bean. */
    @Bean
    @ConditionalOnMissingBean(GatewayErrorRenderer.class)
    public GatewayErrorRenderer gatewayErrorRenderer() {
        return new DefaultGatewayErrorRenderer();
    }

    /**
     * A {@link ProtoJson} in the gateway's own JSON dialect: {@code shiguchi.json.*}, the
     * global print options and every {@link ProtoScalarCodec} bean. Inject it to write or read
     * JSON exactly as the gateway's REST surface does, for instance in a controller, a message
     * listener or a test. Define your own {@link ProtoJson} bean and this one backs off.
     */
    @Bean
    @ConditionalOnMissingBean(ProtoJson.class)
    public ProtoJson gatewayProtoJson(GatewayProperties properties, ObjectProvider<ProtoScalarCodec> codecs) {
        return GatewayJsonSettings.protoJson(
                properties.getJson(), codecs.orderedStream().toList());
    }

    /**
     * Request authorization policy. The default enforces the {@code auth_scopes} declared on the
     * {@code (shiguchi.api.method)} option — a coarse, route-level check. Replace with your own
     * {@link GatewayAuthorizer} bean to add per-object authorization (OpenFGA, OPA, Cedar, ...);
     * delegate to {@link ScopeGatewayAuthorizer} from it to keep the scope gate as well.
     */
    @Bean
    @ConditionalOnMissingBean(GatewayAuthorizer.class)
    public GatewayAuthorizer gatewayAuthorizer() {
        return new ScopeGatewayAuthorizer();
    }
}
