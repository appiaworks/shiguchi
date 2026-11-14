package dev.appiaworks.shiguchi.config;

/**
 * The service-instance metadata keys the gateway reads from a Spring Cloud registry when
 * {@code shiguchi.discovery.auto-register.enabled} is on. A backend publishes them in its
 * registration (Eureka {@code metadata-map}, Consul {@code metadata}, Kubernetes annotations,
 * {@code SimpleDiscoveryClient} instances); the gateway reads them, and nothing else, to decide
 * what to expose.
 *
 * <p>The keys the gateway owns ({@link #EXPOSE}, {@link #TYPE}, {@link #PATH_PREFIX}) sit under a
 * prefix, {@code shiguchi.} by default ({@code shiguchi.expose}); change it with
 * {@code shiguchi.discovery.auto-register.metadata-prefix}. {@link #GRPC_PORT} and the OpenAPI
 * keys keep their plain names: {@code grpc-port} is already honoured when a
 * {@code discovery-name} backend is dialled.
 */
public final class DiscoveryMetadata {

    private DiscoveryMetadata() {}

    /** The default prefix of the gateway-owned keys. */
    public static final String DEFAULT_PREFIX = "shiguchi.";

    /** {@code <prefix>expose}: {@code true} opts the service in. Absent or anything else: ignored. */
    public static final String EXPOSE = "expose";

    /** {@code <prefix>type}: {@code grpc} or {@code rest}. Absent: {@code grpc} when {@link #GRPC_PORT} is set. */
    public static final String TYPE = "type";

    /** {@code <prefix>path-prefix}: the route prefix; the default is {@code /<service-id>}. */
    public static final String PATH_PREFIX = "path-prefix";

    /** The gRPC port when it differs from the registry's (unprefixed, as the name resolver reads it). */
    public static final String GRPC_PORT = "grpc-port";

    /** A full OpenAPI document URL for a REST service (unprefixed). Must be on the instance's own host by default. */
    public static final String OPENAPI_URL = "openapi-url";

    /** The OpenAPI document path on the instance for a REST service (unprefixed). Default {@link #DEFAULT_OPENAPI_PATH}. */
    public static final String OPENAPI_PATH = "openapi-path";

    /** The OpenAPI document path used when a REST service gives neither {@link #OPENAPI_URL} nor {@link #OPENAPI_PATH}. */
    public static final String DEFAULT_OPENAPI_PATH = "/v3/api-docs";

    /** The key under {@code prefix}: {@code key("shiguchi.", EXPOSE)} is {@code shiguchi.expose}. */
    public static String key(String prefix, String name) {
        return (prefix == null ? DEFAULT_PREFIX : prefix) + name;
    }
}
