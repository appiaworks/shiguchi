package dev.appiaworks.shiguchi.ui.autoconfigure;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;

/**
 * The classes {@link ShiguchiUiServletAutoConfiguration}'s component scan leaves out: everything
 * under {@code dev.appiaworks.shiguchi} that needs WebFlux, Spring Cloud Gateway or the reactive
 * security API to even load. Matching is by class <em>name</em>, read from the scan's class
 * metadata, so none of these classes is ever loaded (a class literal would defeat the purpose on
 * a classpath without WebFlux).
 *
 * <p>When a new reactive-only component appears in the gateway modules, add it here. The drift
 * guard test in the webmvc starter fails until the class is listed, guarded by a
 * {@literal @}ConditionalOnClass naming a Spring Cloud Gateway type, or guarded by
 * {@literal @}ConditionalOnWebApplication(type = REACTIVE).
 */
public final class ServletScanExcludes implements TypeFilter {

    /** Fully qualified names of the excluded classes. */
    public static final List<String> CLASS_NAMES = List.of(
            // The gateway's reactive security chain (ServerHttpSecurity) and the web filters.
            "dev.appiaworks.shiguchi.security.GatewaySecurityConfig",
            "dev.appiaworks.shiguchi.gateway.GatewayCorsConfiguration",
            "dev.appiaworks.shiguchi.gateway.ShiguchiFilter",
            "dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar",
            "dev.appiaworks.shiguchi.gateway.GrpcWebFilter",
            "dev.appiaworks.shiguchi.gateway.RequestIdFilter",
            "dev.appiaworks.shiguchi.gateway.GatewayAccessLogFilter",
            "dev.appiaworks.shiguchi.gateway.GatewaySecurityHeadersFilter",
            "dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer",
            // Rate limiting.
            "dev.appiaworks.shiguchi.ratelimit.GatewayRateLimitFilter",
            "dev.appiaworks.shiguchi.ratelimit.RateLimitConfiguration",
            // REST_DOWNSTREAM and HTTP merge sources (WebClient).
            "dev.appiaworks.shiguchi.rest.HttpUpstreamDispatcher",
            "dev.appiaworks.shiguchi.routing.RestDownstreamRouteLoader",
            // Reads the reactive security context; ServletGrpcMetadata replaces it.
            "dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator",
            // The WebFlux flavour of the UI.
            "dev.appiaworks.shiguchi.ui.GrpcUiConfig",
            "dev.appiaworks.shiguchi.ui.GrpcUiController");

    private static final Set<String> NAMES = Set.copyOf(CLASS_NAMES);

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
            throws IOException {
        return NAMES.contains(metadataReader.getClassMetadata().getClassName());
    }
}
