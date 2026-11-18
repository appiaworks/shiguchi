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

    /** Spellings of {@link #GRPC_PORT} also accepted, in order, after the canonical key. */
    public static final java.util.List<String> GRPC_PORT_ALIASES = java.util.List.of("grpc.port", "grpc_port");

    /**
     * The key Spring Cloud Kubernetes discovery gives a Service port named {@code grpc}: its
     * {@code ports-prefix} ({@code port.} by default, {@code spring.cloud.kubernetes.discovery.metadata.ports-prefix})
     * followed by the port name. Honoured last, after every {@link #GRPC_PORT} spelling.
     */
    public static final String KUBERNETES_GRPC_PORT = "port.grpc";

    /**
     * The raw gRPC port metadata value of an instance, or {@code null} when none is published.
     * Keys are tried in order: {@code customKey} (when not blank), {@link #GRPC_PORT}, then the
     * aliases {@code grpc.port}, {@code grpc_port} and {@code gRPC_port}; the first spelling is
     * matched exactly, the rest ignoring case and treating {@code - . _} alike, so
     * {@code GRPC_PORT} and {@code gRPC.port} also match. Last comes {@link #KUBERNETES_GRPC_PORT}
     * ({@code port.grpc}, same case-insensitive matching). A blank value counts as absent.
     */
    public static String grpcPortValue(java.util.Map<String, String> metadata, String customKey) {
        if (metadata == null || metadata.isEmpty()) return null;
        if (customKey != null && !customKey.isBlank()) {
            String v = metadata.get(customKey);
            if (v != null && !v.isBlank()) return v;
        }
        String v = metadata.get(GRPC_PORT);
        if (v != null && !v.isBlank()) return v;
        for (String alias : GRPC_PORT_ALIASES) {
            v = metadata.get(alias);
            if (v != null && !v.isBlank()) return v;
        }
        String k8s = firstNormalized(metadata, "grpc-port");
        if (k8s != null) return k8s;
        v = metadata.get(KUBERNETES_GRPC_PORT);
        if (v != null && !v.isBlank()) return v;
        return firstNormalized(metadata, "port-grpc");
    }

    private static String firstNormalized(java.util.Map<String, String> metadata, String normalizedKey) {
        for (java.util.Map.Entry<String, String> e : metadata.entrySet()) {
            if (e.getKey() != null
                    && e.getValue() != null
                    && !e.getValue().isBlank()
                    && normalizedKey.equals(normalize(e.getKey()))) {
                return e.getValue();
            }
        }
        return null;
    }

    /** {@link #grpcPortValue(java.util.Map, String)} with no custom key. */
    public static String grpcPortValue(java.util.Map<String, String> metadata) {
        return grpcPortValue(metadata, null);
    }

    /**
     * The gRPC port an instance publishes: the {@link #grpcPortValue} parsed, or empty when none
     * is published or it is not a number in 1..65535.
     */
    public static java.util.OptionalInt grpcPort(java.util.Map<String, String> metadata, String customKey) {
        String v = grpcPortValue(metadata, customKey);
        if (v == null) return java.util.OptionalInt.empty();
        try {
            int port = Integer.parseInt(v.trim());
            return port >= 1 && port <= 65535 ? java.util.OptionalInt.of(port) : java.util.OptionalInt.empty();
        } catch (NumberFormatException e) {
            return java.util.OptionalInt.empty();
        }
    }

    /** {@link #grpcPort(java.util.Map, String)} with no custom key. */
    public static java.util.OptionalInt grpcPort(java.util.Map<String, String> metadata) {
        return grpcPort(metadata, null);
    }

    private static String normalize(String key) {
        return key.trim().toLowerCase(java.util.Locale.ROOT).replace('.', '-').replace('_', '-');
    }

    /** The key under {@code prefix}: {@code key("shiguchi.", EXPOSE)} is {@code shiguchi.expose}. */
    public static String key(String prefix, String name) {
        return (prefix == null ? DEFAULT_PREFIX : prefix) + name;
    }
}
