package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

/**
 * gRPC name resolver for {@code discovery:///<service-id>} targets, backed by Spring Cloud's
 * {@link DiscoveryClient} (Eureka, Consul, Kubernetes, ...).
 *
 * <p>Every registered instance becomes one address of the channel, so with the channel's
 * {@code round_robin} policy calls spread across all of them and an instance whose transport
 * fails is skipped until it answers again. The instance list is re-read every
 * {@code shiguchi.client.discovery-refresh} (30 s by default) and whenever gRPC asks for a
 * refresh after a connection failure, so scale-outs and rolling restarts are followed without a
 * schema refresh or a gateway restart. An instance metadata entry {@code grpc-port} overrides the
 * registry port for services that register their HTTP port but serve gRPC on another.
 *
 * <p>Registered with gRPC's default {@link NameResolverRegistry} when the bean is created and
 * removed when it is destroyed; {@link GrpcChannelFactory} builds {@code discovery:///} targets
 * only while a {@code DiscoveryClient} bean exists. Registry lookups and hostname resolution run
 * on the resolver's own thread, never on the thread that builds the channel or issues the call.
 */
@Component
@ConditionalOnClass(DiscoveryClient.class)
public class DiscoveryNameResolverProvider extends NameResolverProvider implements DisposableBean {

    public static final String SCHEME = "discovery";

    /**
     * The registry metadata of the instance behind an address, attached by this resolver so a
     * load-balancing policy can route by it (the version, for {@code services[].versions.traffic}).
     */
    public static final io.grpc.Attributes.Key<Map<String, String>> INSTANCE_METADATA =
            io.grpc.Attributes.Key.create("shiguchi.instance-metadata");

    /** Providers created in this JVM, for a scheme of their own; see {@link #scheme}. */
    private static final java.util.concurrent.atomic.AtomicInteger INSTANCES =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * This provider's scheme: {@code discovery} for the first one in the JVM, {@code discovery-2},
     * {@code discovery-3}... after it. gRPC's name-resolver registry is process-wide and picks one
     * provider per scheme, so with several application contexts alive at once — a test JVM caching
     * contexts, a parent and child context — a shared scheme would let one context's channels
     * resolve through another context's registry, which knows none of their services.
     */
    private final String scheme;

    private static final Logger log = LoggerFactory.getLogger(DiscoveryNameResolverProvider.class);

    private final ObjectProvider<DiscoveryClient> discoveryClient;
    private final Duration refreshInterval;
    private final ScheduledExecutorService scheduler;
    private final boolean registered;

    @Autowired
    public DiscoveryNameResolverProvider(
            ObjectProvider<DiscoveryClient> discoveryClient, GatewayProperties properties) {
        this(discoveryClient, properties.getClient().getDiscoveryRefresh(), true);
    }

    DiscoveryNameResolverProvider(
            ObjectProvider<DiscoveryClient> discoveryClient, Duration refreshInterval, boolean register) {
        this.discoveryClient = discoveryClient;
        this.refreshInterval = refreshInterval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "grpc-discovery-resolver");
            t.setDaemon(true);
            return t;
        });
        this.registered = register;
        int n = INSTANCES.incrementAndGet();
        this.scheme = n == 1 ? SCHEME : SCHEME + "-" + n;
        if (register) NameResolverRegistry.getDefaultRegistry().register(this);
    }

    /**
     * Where instance counts are reported, when metrics are on the classpath. A setter rather than
     * a constructor argument: this class already has two constructors with an {@code @Autowired}
     * one among them, and adding a parameter to either is how that wiring breaks.
     */
    @Autowired(required = false)
    public void setStateMetrics(dev.appiaworks.shiguchi.gateway.GatewayStateMetrics stateMetrics) {
        this.stateMetrics = stateMetrics;
    }

    private volatile dev.appiaworks.shiguchi.gateway.GatewayStateMetrics stateMetrics;

    /** True when a {@link DiscoveryClient} bean exists, i.e. {@code discovery:///} targets resolve. */
    public boolean isDiscoveryAvailable() {
        return discoveryClient.getIfAvailable() != null;
    }

    /**
     * gRPC's own availability check, consulted at registration. Always true: the provider is
     * registered regardless of whether a {@code DiscoveryClient} exists (a registry refuses an
     * unavailable provider), and {@link GrpcChannelFactory} checks {@link #isDiscoveryAvailable()}
     * before building a {@code discovery:///} target.
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /** The channel target for a service id: {@code discovery:///orders}. */
    public static String target(String serviceId) {
        return SCHEME + ":///" + serviceId;
    }

    /** The channel target for a service id under this provider's own scheme. */
    public String targetFor(String serviceId) {
        return scheme + ":///" + serviceId;
    }

    @Override
    public void destroy() {
        if (registered) NameResolverRegistry.getDefaultRegistry().deregister(this);
        scheduler.shutdownNow();
    }

    /**
     * Below the DNS provider's 5 on purpose: gRPC takes the default scheme for scheme-less
     * targets ({@code host:port}) from the highest-priority provider, and that must stay
     * {@code dns}. An explicit {@code discovery:///} target still reaches this provider, because
     * the DNS provider declines every scheme but its own.
     */
    @Override
    protected int priority() {
        return 4;
    }

    @Override
    public String getDefaultScheme() {
        return scheme;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        if (!scheme.equals(targetUri.getScheme())) return null;
        String serviceId = targetUri.getPath();
        if (serviceId == null || serviceId.isBlank()) serviceId = targetUri.getAuthority();
        if (serviceId == null || serviceId.isBlank()) return null;
        serviceId = serviceId.startsWith("/") ? serviceId.substring(1) : serviceId;
        return new DiscoveryNameResolver(serviceId);
    }

    /** The registered instances of a service id right now; empty without a {@link DiscoveryClient}. */
    public List<ServiceInstance> instances(String serviceId) {
        DiscoveryClient client = discoveryClient.getIfAvailable();
        if (client == null) return List.of();
        List<ServiceInstance> instances = client.getInstances(serviceId);
        return instances == null ? List.of() : instances;
    }

    /** Addresses for one service id as the registry reports them right now. */
    List<EquivalentAddressGroup> resolve(String serviceId) {
        DiscoveryClient client = discoveryClient.getIfAvailable();
        if (client == null) return List.of();
        List<EquivalentAddressGroup> groups = new ArrayList<>();
        for (ServiceInstance instance : client.getInstances(serviceId)) {
            Map<String, String> metadata =
                    instance.getMetadata() == null ? Map.of() : Map.copyOf(instance.getMetadata());
            groups.add(new EquivalentAddressGroup(
                    new InetSocketAddress(instance.getHost(), grpcPort(instance)),
                    io.grpc.Attributes.newBuilder()
                            .set(INSTANCE_METADATA, metadata)
                            .build()));
        }
        // Reported on every resolution, including the periodic refresh, so a backend that loses
        // its last instance shows as a gauge hitting zero rather than as calls quietly failing.
        dev.appiaworks.shiguchi.gateway.GatewayStateMetrics metrics = stateMetrics;
        if (metrics != null) metrics.recordDiscoveryInstances(serviceId, groups.size());
        return groups;
    }

    static int grpcPort(ServiceInstance instance) {
        String metaPort =
                instance.getMetadata() == null ? null : instance.getMetadata().get("grpc-port");
        if (metaPort != null) {
            try {
                return Integer.parseInt(metaPort.trim());
            } catch (NumberFormatException e) {
                log.warn(
                        "Instance {} has non-numeric grpc-port metadata '{}'; using registry port {}",
                        instance.getInstanceId(),
                        metaPort,
                        instance.getPort());
            }
        }
        return instance.getPort();
    }

    /** One channel's view of one service id: resolves now, then on every tick and every gRPC refresh. */
    private final class DiscoveryNameResolver extends NameResolver {

        private final String serviceId;
        private final AtomicBoolean resolving = new AtomicBoolean();
        private volatile Listener2 listener;
        private volatile ScheduledFuture<?> tick;
        private volatile List<EquivalentAddressGroup> last = List.of();

        DiscoveryNameResolver(String serviceId) {
            this.serviceId = serviceId;
        }

        @Override
        public String getServiceAuthority() {
            return serviceId;
        }

        @Override
        public void start(Listener2 listener) {
            this.listener = listener;
            long millis = Math.max(refreshInterval.toMillis(), 100);
            tick = scheduler.scheduleWithFixedDelay(this::resolveNow, 0, millis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void refresh() {
            scheduler.execute(this::resolveNow);
        }

        @Override
        public void shutdown() {
            ScheduledFuture<?> t = tick;
            if (t != null) t.cancel(false);
        }

        private void resolveNow() {
            Listener2 l = listener;
            if (l == null || !resolving.compareAndSet(false, true)) return;
            try {
                List<EquivalentAddressGroup> groups = DiscoveryNameResolverProvider.this.resolve(serviceId);
                if (groups.isEmpty()) {
                    log.warn("Discovery returned no instances for service id '{}'", serviceId);
                    l.onError(Status.UNAVAILABLE.withDescription(
                            "discovery returned no instances for service id '" + serviceId + "'"));
                    return;
                }
                if (!groups.equals(last)) {
                    log.info("Resolved '{}' via discovery: {} instance(s) {}", serviceId, groups.size(), groups);
                    last = groups;
                }
                l.onResult(ResolutionResult.newBuilder().setAddresses(groups).build());
            } catch (RuntimeException e) {
                log.warn("Discovery lookup for service id '{}' failed: {}", serviceId, e.toString());
                l.onError(Status.UNAVAILABLE
                        .withDescription("discovery lookup failed: " + e.getMessage())
                        .withCause(e));
            } finally {
                resolving.set(false);
            }
        }
    }
}
