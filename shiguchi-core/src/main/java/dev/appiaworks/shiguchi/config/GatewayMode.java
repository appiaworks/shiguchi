package dev.appiaworks.shiguchi.config;

/** How a backend's schema is discovered, and what protocol it speaks. */
public enum GatewayMode {
    /** gRPC backend — uses server reflection for route discovery. */
    DYNAMIC,
    /** gRPC backend — uses compiled Java stubs on the classpath. */
    STATIC,
    /**
     * gRPC backend — loads the proto descriptor graph from a pre-compiled
     * {@code FileDescriptorSet} (.pb) file specified by {@code pbDescriptorPath}.
     */
    PB_FILE,
    /**
     * Traditional REST backend — fetches routes from openapiUrl, proxies via SCG,
     * optionally wraps in GraphQL.
     */
    REST_DOWNSTREAM
}
