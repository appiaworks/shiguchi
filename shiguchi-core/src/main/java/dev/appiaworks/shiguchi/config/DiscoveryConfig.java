package dev.appiaworks.shiguchi.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code shiguchi.discovery}: building routes from what a Spring Cloud registry lists. */
@Data
@NoArgsConstructor
public class DiscoveryConfig {

    /** Registers the registry's services that opt in through instance metadata. Off by default. */
    private AutoRegisterConfig autoRegister = new AutoRegisterConfig();

    /**
     * Lets a registered REST service's {@code openapi-url} metadata point at a host other than the
     * instance's own. Off by default: instance metadata is writable by whoever can register in the
     * registry, so a foreign document host would let them make the gateway fetch an arbitrary URL.
     * Requests are still proxied to the instance, never to the document's host.
     */
    private boolean allowForeignOpenapiHost = false;

    @Data
    @NoArgsConstructor
    public static class AutoRegisterConfig {

        /**
         * Lists the registry on a schedule and routes every service that opts in with
         * {@code <metadata-prefix>expose=true}. Off by default: turning it on exposes any opted-in
         * service through the gateway, so anyone who can register an instance can publish one.
         * Needs a Spring Cloud {@code DiscoveryClient} bean.
         */
        private boolean enabled = false;

        /**
         * How often the registry is listed. Unset: {@code shiguchi.client.discovery-refresh}.
         * Minimum 100 ms.
         */
        private java.time.Duration interval;

        /**
         * Consecutive syncs a registered service may be missing (gone from the registry, no
         * instances, or no longer opted in) before its routes are removed. Absorbs the flapping a
         * registry shows while evicting and re-registering instances. Minimum 1.
         */
        private int removalGraceSyncs = 3;

        /** Glob patterns ({@code *}, {@code ?}) on the service id; when set, only matching ids are routed. */
        private List<String> include = new ArrayList<>();

        /** Glob patterns on the service id that are never routed; wins over {@link #include}. */
        private List<String> exclude = new ArrayList<>();

        /** Prefix of the gateway-owned metadata keys ({@code expose}, {@code type}, {@code path-prefix}). */
        private String metadataPrefix = DiscoveryMetadata.DEFAULT_PREFIX;
    }
}
