package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code shiguchi} in the application's configuration.
 *
 * <p>The binding root and nothing else: each field is one area of the gateway, and the type
 * holding it carries that area's documentation. Adding a property means adding it to the
 * relevant config class, not here.
 *
 * <p>Bound by name, so the YAML is stable even when these classes move. Validated while Spring
 * binds it — see {@link GatewayPropertiesValidator}, which fails startup with the property path
 * and the reason rather than letting a typo surface as odd behaviour later.
 */
@Data
@ConfigurationProperties(prefix = "shiguchi")
public class GatewayProperties {

    private GraphQlConfig graphql = new GraphQlConfig();
    private RestConfig rest = new RestConfig();
    private OpenApiConfig openapi = new OpenApiConfig();
    private RefreshConfig refresh = new RefreshConfig();
    private GrpcConfig grpc = new GrpcConfig();
    private ClientConfig client = new ClientConfig();
    private UiConfig ui = new UiConfig();
    private SecurityConfig security = new SecurityConfig();
    private AccessLogConfig accessLog = new AccessLogConfig();
    private CorsConfig cors = new CorsConfig();

    /** Security response headers on the browser surfaces. */
    private HeadersConfig headers = new HeadersConfig();

    /** The per-request id that reaches the logs and the caller. */
    private RequestIdConfig requestId = new RequestIdConfig();

    private List<ServiceDefinition> services = new ArrayList<>();
    private MergeConfig merge = new MergeConfig();

    /** Avro on the gateway's own REST surface (needs {@code shiguchi-avro-*-starter}). */
    private AvroSurfaceConfig avro = new AvroSurfaceConfig();

    /** Backend health reporting and the readiness policy. */
    private HealthConfig health = new HealthConfig();

    /** Inbound rate limiting on the REST and GraphQL surfaces (the proxy port has its own, under {@code grpc}). */
    private InboundRateLimitConfig rateLimit = new InboundRateLimitConfig();

    /** What happens when a configured backend cannot be loaded at startup. */
    private StartupConfig startup = new StartupConfig();

    /** JSON forms of dates, times, decimals and durations on the REST and GraphQL surfaces. */
    private JsonConfig json = new JsonConfig();

    /** gRPC-Web for browser clients, on the HTTP port. Off by default. */
    private GrpcWebConfig grpcWeb = new GrpcWebConfig();
}
