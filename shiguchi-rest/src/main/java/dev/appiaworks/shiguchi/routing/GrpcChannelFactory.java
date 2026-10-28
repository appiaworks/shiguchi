package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ClientConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GrpcLoggingInterceptor;
import dev.appiaworks.shiguchi.gateway.GrpcMetricsInterceptor;
import dev.appiaworks.shiguchi.grpc.GrpcCallContext;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.TlsChannelCredentials;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.stub.MetadataUtils;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for building the outbound {@link ManagedChannel} the gateway uses to
 * reach each downstream gRPC service.
 *
 * <p>One channel is created per service (by {@link DynamicRouteLoader} / {@link StaticRouteLoader})
 * and reused for every call — gRPC multiplexes all RPCs over the channel's HTTP/2 connections, so
 * no external connection pool is required. This factory applies the shared interceptor stack,
 * OpenTelemetry wiring, per-service static metadata, transport credentials (plaintext, system
 * TLS, or the per-service {@code tls} block including mTLS), and the client-side tuning from
 * {@link ClientConfig} (keepalive, idle timeout, message-size limits).
 *
 * <p>Where the channel points, in order of precedence:
 * <ol>
 *   <li>{@code target}: a raw gRPC target such as {@code dns:///orders.ns.svc:9090} or
 *       {@code xds:///orders} — gRPC's own resolvers and load balancing apply;</li>
 *   <li>{@code discovery-name} with a Spring Cloud {@code DiscoveryClient} on the classpath:
 *       {@code discovery:///name}, resolved by {@link DiscoveryNameResolverProvider} to every
 *       registered instance and balanced across them;</li>
 *   <li>{@code discovery-name} with a custom {@link GrpcServiceAddressResolver} bean: the one
 *       address that resolver returns;</li>
 *   <li>{@code host} / {@code port}.</li>
 * </ol>
 * The first two default to {@code round_robin}; {@code load-balancing-policy} overrides.
 *
 * <p>Interceptor order is significant and preserved exactly:
 * {@code metrics → logging → GrpcCallContext} (channel-level), then OpenTelemetry, then the
 * per-service static-metadata interceptor.
 *
 * <p>{@link GrpcChannelBuilderCustomizer} beans run last, in {@code @Order} order, so user
 * customizations always see (and may override) the gateway's own configuration.
 */
@Component
public class GrpcChannelFactory {

    /** Target prefix of a server in this JVM: {@code inprocess:<name>}, dialed with {@link InProcessChannelBuilder}. */
    public static final String IN_PROCESS = "inprocess:";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GrpcChannelFactory.class);

    static {
        // gRPC must know the version-routing policy before any channel parses a config naming it.
        VersionedLoadBalancerProvider.register();
    }

    private final ClientConfig clientConfig;
    private final GrpcMetricsInterceptor metricsInterceptor;
    private final GrpcLoggingInterceptor loggingInterceptor;
    private final Optional<GrpcOpenTelemetry> grpcOpenTelemetry;
    private final List<GrpcChannelBuilderCustomizer> customizers;
    private final List<GrpcServiceAddressResolver> addressResolvers;
    private final ObjectProvider<DiscoveryNameResolverProvider> discoveryResolver;

    /** The live traffic split of each version-routed service, by name; kept across channel rebuilds. */
    private final Map<String, TrafficControl> trafficControls = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public GrpcChannelFactory(
            GatewayProperties properties,
            GrpcMetricsInterceptor metricsInterceptor,
            GrpcLoggingInterceptor loggingInterceptor,
            Optional<GrpcOpenTelemetry> grpcOpenTelemetry,
            ObjectProvider<GrpcChannelBuilderCustomizer> customizers,
            ObjectProvider<GrpcServiceAddressResolver> addressResolvers,
            ObjectProvider<DiscoveryNameResolverProvider> discoveryResolver) {
        this.clientConfig = properties.getClient();
        this.metricsInterceptor = metricsInterceptor;
        this.loggingInterceptor = loggingInterceptor;
        this.grpcOpenTelemetry = grpcOpenTelemetry;
        this.customizers = customizers.orderedStream().toList();
        this.addressResolvers = addressResolvers.orderedStream().toList();
        this.discoveryResolver = discoveryResolver;
    }

    /** Without a discovery resolver: {@code discovery-name} falls back to the address-resolver SPI. */
    public GrpcChannelFactory(
            GatewayProperties properties,
            GrpcMetricsInterceptor metricsInterceptor,
            GrpcLoggingInterceptor loggingInterceptor,
            Optional<GrpcOpenTelemetry> grpcOpenTelemetry,
            ObjectProvider<GrpcChannelBuilderCustomizer> customizers,
            ObjectProvider<GrpcServiceAddressResolver> addressResolvers) {
        this(
                properties,
                metricsInterceptor,
                loggingInterceptor,
                grpcOpenTelemetry,
                customizers,
                addressResolvers,
                new ObjectProvider<>() {
                    @Override
                    public DiscoveryNameResolverProvider getObject() {
                        throw new IllegalStateException("no discovery resolver");
                    }

                    @Override
                    public DiscoveryNameResolverProvider getIfAvailable() {
                        return null;
                    }
                });
    }

    /**
     * Builds a fully-configured {@link ManagedChannel} for one downstream service. The caller owns
     * the returned channel's lifecycle (it is stored on the {@code ServiceEndpointContext}).
     */
    public ManagedChannel build(ServiceDefinition svcDef) {
        return build(svcDef, Set.of());
    }

    /**
     * Builds the channel with a retry policy that skips {@code nonRetryableMethods}.
     *
     * <p>Callers pass the methods that are unsafe to repeat once the service's descriptors have
     * been read. Until then the methods are unknown — in {@code DYNAMIC} mode the channel is what
     * reflection runs over — so a first channel is built without exclusions and the route loader
     * rebuilds it through this method once it knows the routes.
     */
    public ManagedChannel build(ServiceDefinition svcDef, Set<String> nonRetryableMethods) {
        // One breaker and one concurrency limit per service, however many channels carry its calls:
        // state split across the pool would let each channel trip, or admit, on its own.
        ClientInterceptor breaker =
                svcDef.getCircuitBreaker() != null && svcDef.getCircuitBreaker().isEnabled()
                        ? new CircuitBreakerInterceptor(svcDef.getName(), svcDef.getCircuitBreaker())
                        : null;
        ClientInterceptor limiter = svcDef.getMaxConcurrentCalls() > 0
                ? new ConcurrencyLimitInterceptor(svcDef.getName(), svcDef.getMaxConcurrentCalls())
                : null;
        int channels = Math.max(1, svcDef.getChannels());
        if (channels == 1) return buildOne(svcDef, nonRetryableMethods, breaker, limiter);
        List<ManagedChannel> members = new java.util.ArrayList<>(channels);
        for (int i = 0; i < channels; i++) {
            members.add(buildOne(svcDef, nonRetryableMethods, breaker, limiter));
        }
        log.info("Service '{}': spreading calls over {} channels", svcDef.getName(), channels);
        return new ChannelPool(members);
    }

    private ManagedChannel buildOne(
            ServiceDefinition svcDef,
            Set<String> nonRetryableMethods,
            ClientInterceptor breaker,
            ClientInterceptor limiter) {
        ChannelCredentials credentials = channelCredentials(svcDef);
        ManagedChannelBuilder<?> channelBuilder;
        String policy = svcDef.getLoadBalancingPolicy();

        if (hasText(svcDef.getTarget())
                && !svcDef.isTargetDerived()
                && svcDef.getTarget().startsWith(IN_PROCESS)) {
            // A server inside this JVM: the gateway's own hosted services (the push service), or a
            // test's. gRPC has no name resolver for the scheme, so the builder is chosen here.
            channelBuilder = InProcessChannelBuilder.forName(svcDef.getTarget().substring(IN_PROCESS.length()));
        } else if (hasText(svcDef.getTarget()) && !svcDef.isTargetDerived()) {
            channelBuilder = Grpc.newChannelBuilder(svcDef.getTarget(), credentials);
            if (!hasText(policy)) policy = "round_robin";
        } else if (hasText(svcDef.getDiscoveryName()) && discoveryAvailable()) {
            channelBuilder = Grpc.newChannelBuilder(
                    discoveryResolver.getIfAvailable().targetFor(svcDef.getDiscoveryName()), credentials);
            if (!hasText(policy)) policy = "round_robin";
        } else {
            GrpcServiceAddressResolver.ResolvedAddress address = resolveAddress(svcDef);
            channelBuilder = Grpc.newChannelBuilderForAddress(address.host(), address.port(), credentials);
        }
        if (hasText(policy)) channelBuilder.defaultLoadBalancingPolicy(policy);

        channelBuilder.intercept(metricsInterceptor, loggingInterceptor, GrpcCallContext.INTERCEPTOR);

        applyClientConfig(channelBuilder, svcDef);

        TrafficControl control = controlFor(svcDef);
        applyServiceConfig(channelBuilder, svcDef, nonRetryableMethods, policy, control);
        if (control != null) {
            // Marks unary calls, whose latency the version policy records per version.
            channelBuilder.intercept(UNARY_MARKER);
        }

        if (breaker != null) {
            // Outside the retry policy on purpose: gRPC retries happen below this interceptor, so
            // the breaker sees one outcome per logical call rather than one per attempt.
            channelBuilder.intercept(breaker);
        }
        if (limiter != null) {
            // Added after the breaker, so it runs before it: a call turned away for the limit never
            // reaches the breaker, and an overloaded gateway does not count against the backend.
            channelBuilder.intercept(limiter);
        }

        grpcOpenTelemetry.ifPresent(t -> t.configureChannelBuilder(channelBuilder));

        if (!svcDef.getStaticMetadata().isEmpty()) {
            channelBuilder.intercept(staticMetadataInterceptor(svcDef.getStaticMetadata()));
        }

        String compression = compressorName(svcDef);
        if (compression != null) {
            channelBuilder.intercept(compressionInterceptor(compression));
            log.info("Service '{}': compressing outbound calls with {}", svcDef.getName(), compression);
        }

        for (GrpcChannelBuilderCustomizer customizer : customizers) {
            customizer.customize(svcDef, channelBuilder);
        }

        return channelBuilder.build();
    }

    /**
     * Once a service's routes are known, swaps its channel for one whose retry policy skips the
     * methods that are unsafe to repeat. A no-op unless retries are on and at least one method has
     * to be excluded, so the common case costs nothing.
     *
     * <p>The replaced channel has served only descriptor loading, so shutting it down loses
     * nothing in flight. Called during the build, before the context is published.
     */
    public void applyRetryExclusions(ServiceEndpointContext ctx, ServiceDefinition svcDef) {
        Set<String> excluded = RetryServiceConfig.nonRetryableMethods(svcDef, ctx.getRoutes());
        if (excluded.isEmpty()) return;

        ManagedChannel previous = ctx.replaceChannel(build(svcDef, excluded));
        previous.shutdown();
        log.info(
                "Service '{}': {} of {} routes are on methods not declared idempotent, so they will not be"
                        + " retried or hedged: {}. Declare idempotency_level in the proto, or set"
                        + " retry.retry-non-idempotent / hedging.hedge-non-idempotent: true to repeat them"
                        + " regardless.",
                svcDef.getName(),
                excluded.size(),
                ctx.getRoutes().size(),
                excluded.size() <= 5 ? String.join(", ", excluded) : excluded.size() + " methods");
    }

    /**
     * The one instance {@code svcDef}'s schema should be read from, for a {@code discovery-name}
     * service whose registry is available; empty otherwise. See {@link SchemaSourceSelector}. A
     * split changed at runtime counts: the schema comes from the versions receiving calls now.
     */
    public Optional<SchemaSourceSelector.SchemaSource> schemaSource(ServiceDefinition svcDef) {
        TrafficControl control = trafficControls.get(svcDef.getName());
        return SchemaSourceSelector.select(
                svcDef, discoveryResolver.getIfAvailable(), control == null ? null : control.currentWeights());
    }

    /**
     * The live traffic split of a version-routed service ({@code versions.traffic} or a preview
     * header on a {@code discovery-name} service), once its channel has been built; empty for any
     * other service.
     */
    public Optional<TrafficControl> trafficControl(String serviceName) {
        return Optional.ofNullable(trafficControls.get(serviceName));
    }

    /** Every version-routed service's live split, by service name. */
    public Map<String, TrafficControl> trafficControls() {
        return java.util.Collections.unmodifiableMap(trafficControls);
    }

    private static final ClientInterceptor UNARY_MARKER = new ClientInterceptor() {
        @Override
        public <Q, S> ClientCall<Q, S> interceptCall(
                io.grpc.MethodDescriptor<Q, S> method, io.grpc.CallOptions options, io.grpc.Channel next) {
            return next.newCall(
                    method,
                    method.getType() == io.grpc.MethodDescriptor.MethodType.UNARY
                            ? options.withOption(TrafficControl.UNARY, true)
                            : options);
        }
    };

    /** The control of a service whose calls are routed by version, created with its first channel. */
    private TrafficControl controlFor(ServiceDefinition svcDef) {
        if (!routesByVersion(svcDef)) return null;
        return trafficControls.computeIfAbsent(svcDef.getName(), name -> {
            List<VersionedLoadBalancerProvider.VersionWeight> configured = new java.util.ArrayList<>();
            if (svcDef.getVersions().getTraffic() != null) {
                for (ServiceDefinition.TrafficSplit split : svcDef.getVersions().getTraffic()) {
                    configured.add(new VersionedLoadBalancerProvider.VersionWeight(
                            split.getVersion().trim(), Math.max(0, split.getWeight())));
                }
            }
            return TrafficControl.create(name, configured);
        });
    }

    private static boolean routesByVersion(ServiceDefinition svcDef) {
        return hasText(svcDef.getDiscoveryName())
                && svcDef.isTargetDerived()
                && svcDef.getVersions() != null
                && svcDef.getVersions().routesByVersion();
    }

    /** Releases the traffic controls, so a closed application context leaves none behind. */
    @jakarta.annotation.PreDestroy
    public void close() {
        trafficControls.values().forEach(TrafficControl::release);
    }

    /** Instances per published version, oldest first, for a {@code discovery-name} service. */
    public Map<String, Integer> instanceVersions(ServiceDefinition svcDef) {
        return SchemaSourceSelector.instanceCounts(svcDef, discoveryResolver.getIfAvailable());
    }

    /**
     * A channel straight to one instance, for reading its schema: the service's credentials,
     * client settings, static metadata and customizers, but no load balancing, retries, breaker,
     * limit or pool — a reflection call must reach exactly this instance, and is not traffic.
     * The caller shuts it down.
     */
    public ManagedChannel buildSchemaChannel(ServiceDefinition svcDef, SchemaSourceSelector.SchemaSource source) {
        ManagedChannelBuilder<?> builder =
                Grpc.newChannelBuilderForAddress(source.host(), source.port(), channelCredentials(svcDef));
        builder.intercept(loggingInterceptor, GrpcCallContext.INTERCEPTOR);
        applyClientConfig(builder, svcDef);
        if (!svcDef.getStaticMetadata().isEmpty()) {
            builder.intercept(staticMetadataInterceptor(svcDef.getStaticMetadata()));
        }
        for (GrpcChannelBuilderCustomizer customizer : customizers) {
            customizer.customize(svcDef, builder);
        }
        return builder.build();
    }

    private boolean discoveryAvailable() {
        DiscoveryNameResolverProvider provider = discoveryResolver.getIfAvailable();
        return provider != null && provider.isDiscoveryAvailable();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Resolves the backend address for a static {@code host}/{@code port} or, for a
     * {@code discovery-name} without a Spring Cloud {@code DiscoveryClient}, through the registered
     * {@link GrpcServiceAddressResolver}s (first non-empty answer wins). Fails fast when a
     * discovery name cannot be resolved — the route loaders catch this and retry after startup.
     */
    private GrpcServiceAddressResolver.ResolvedAddress resolveAddress(ServiceDefinition svcDef) {
        if (!hasText(svcDef.getDiscoveryName())) {
            return new GrpcServiceAddressResolver.ResolvedAddress(svcDef.getHost(), svcDef.getPort());
        }
        return addressResolvers.stream()
                .map(r -> r.resolve(svcDef))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Service '" + svcDef.getName()
                        + "': discovery-name '" + svcDef.getDiscoveryName()
                        + "' could not be resolved. Ensure a Spring Cloud DiscoveryClient is on the"
                        + " classpath and the service is registered, or provide a"
                        + " GrpcServiceAddressResolver bean."));
    }

    /**
     * Resolves the transport credentials for a service: the per-service {@code tls} block wins
     * (custom CA and/or mTLS client identity); otherwise the {@code insecure} flag selects
     * plaintext or system-default TLS.
     */
    private static ChannelCredentials channelCredentials(ServiceDefinition svcDef) {
        ServiceDefinition.TlsConfig tls = svcDef.getTls();
        if (tls == null) {
            return svcDef.isInsecure() ? InsecureChannelCredentials.create() : TlsChannelCredentials.create();
        }
        try {
            TlsChannelCredentials.Builder builder = TlsChannelCredentials.newBuilder();
            if (tls.getTrustCertCollection() != null) {
                builder.trustManager(new File(tls.getTrustCertCollection()));
            }
            if (tls.getCertChain() != null || tls.getPrivateKey() != null) {
                if (tls.getCertChain() == null || tls.getPrivateKey() == null) {
                    throw new IllegalStateException("Service '" + svcDef.getName()
                            + "': tls.cert-chain and tls.private-key must both be set for mTLS");
                }
                builder.keyManager(new File(tls.getCertChain()), new File(tls.getPrivateKey()));
            }
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("Service '" + svcDef.getName() + "': failed to load TLS material", e);
        }
    }

    /**
     * Installs the service's {@code retry} (or {@code hedging}) block and its {@code outlier-detection}
     * block as one gRPC service config, so attempts are re-resolved and re-balanced by the channel,
     * gRPC's own retry throttling applies, and outlier instances are ejected by the load balancer.
     * Left untouched when all of them are off, which keeps the channel on gRPC's defaults.
     *
     * @param lbPolicy the channel's load-balancing policy, which outlier detection wraps; null for
     *     gRPC's default
     */
    private static void applyServiceConfig(
            ManagedChannelBuilder<?> builder,
            ServiceDefinition svcDef,
            Set<String> nonRetryableMethods,
            String lbPolicy,
            TrafficControl control) {
        Map<String, Object> serviceConfig = RetryServiceConfig.forService(svcDef, nonRetryableMethods);
        // Routing by version sits below outlier detection: ejection still judges every instance,
        // and the version policy only sees the ones left in rotation.
        Map<String, Object> versioned = hasText(svcDef.getDiscoveryName()) && svcDef.isTargetDerived()
                ? VersionedLoadBalancingConfig.forService(
                        svcDef, hasText(lbPolicy) ? lbPolicy : "round_robin", control == null ? null : control.id())
                : null;
        List<Map<String, Object>> child = versioned != null
                ? List.of(versioned)
                : List.of(Map.of(hasText(lbPolicy) ? lbPolicy : "pick_first", Map.of()));
        List<Map<String, Object>> outlierDetection = OutlierDetectionServiceConfig.loadBalancingConfig(svcDef, child);
        if (outlierDetection != null) {
            if (!hasText(lbPolicy)) {
                log.warn(
                        "Service '{}': outlier-detection is on but the channel has a single address (host/port);"
                                + " there is nothing to eject. Use discovery-name or a target that resolves to"
                                + " several instances.",
                        svcDef.getName());
            }
            serviceConfig = serviceConfig == null
                    ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(serviceConfig);
            // A service config's loadBalancingConfig replaces defaultLoadBalancingPolicy; the child
            // policy inside it is that same policy, so balancing is unchanged apart from ejection.
            serviceConfig.put("loadBalancingConfig", outlierDetection);
            log.info("Service '{}': outlier detection on", svcDef.getName());
        }
        if (outlierDetection == null && versioned != null) {
            serviceConfig = serviceConfig == null
                    ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(serviceConfig);
            serviceConfig.put("loadBalancingConfig", child);
        }
        if (versioned != null) log.info("Service '{}': routing calls by version", svcDef.getName());
        if (serviceConfig == null) return;
        // disableServiceConfigLookUp stops a backend-published config from silently replacing
        // this one; the gateway's YAML should be the last word on how its calls are retried.
        builder.disableServiceConfigLookUp().defaultServiceConfig(serviceConfig).enableRetry();
    }

    private void applyClientConfig(ManagedChannelBuilder<?> builder, ServiceDefinition svcDef) {
        builder.keepAliveTime(clientConfig.getKeepAliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(clientConfig.getKeepAliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(clientConfig.isKeepAliveWithoutCalls())
                .maxInboundMessageSize(svcDef.effectiveMaxInboundMessageSize(clientConfig.getMaxInboundMessageSize()));

        long idleMillis = clientConfig.getIdleTimeout().toMillis();
        if (idleMillis > 0) {
            builder.idleTimeout(idleMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The compressor to use for this service's outbound calls, or {@code null} for none.
     *
     * <p>{@code identity} and a blank value both mean "do not compress", which is gRPC's own
     * name for the no-op compressor and the default. Anything else is passed to gRPC as-is:
     * {@code gzip} is registered out of the box, and a custom codec registered in
     * {@link io.grpc.CompressorRegistry} works without this class knowing about it.
     */
    private static String compressorName(ServiceDefinition svcDef) {
        String configured = svcDef.getCompression();
        if (configured == null || configured.isBlank()) return null;
        String trimmed = configured.trim();
        return "identity".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    /**
     * Sets the compressor on every call the channel makes.
     *
     * <p>A call option rather than a channel setting because gRPC has no channel-wide equivalent:
     * compression is chosen per call, so applying it to all of them is what an interceptor is
     * for.
     */
    private static ClientInterceptor compressionInterceptor(String compressor) {
        return new ClientInterceptor() {
            @Override
            public <Q, S> ClientCall<Q, S> interceptCall(
                    MethodDescriptor<Q, S> method, CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions.withCompression(compressor));
            }
        };
    }

    /**
     * Builds a channel-level interceptor that appends static key/value pairs to every outbound
     * call's metadata.
     */
    private static ClientInterceptor staticMetadataInterceptor(Map<String, String> headers) {
        Metadata meta = new Metadata();
        headers.forEach((k, v) -> meta.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v));
        return MetadataUtils.newAttachHeadersInterceptor(meta);
    }
}
