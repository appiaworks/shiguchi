package dev.appiaworks.shiguchi.routing;

import com.google.api.AnnotationsProto;
import com.google.api.HttpRule;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.ScgRouteRegistrar;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.GraphQlRule;
import dev.appiaworks.shiguchi.reflection.GrpcReflectionClient;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Populates GrpcRouteRegistry at startup for all DYNAMIC mode services.
 *
 * For each service:
 *  1. Open a ManagedChannel to host:port.
 *  2. Use GrpcReflectionClient to list services and resolve FileDescriptorSets.
 *  3. Build a ServiceEndpointContext with descriptors, type registry, method index.
 *  4. Parse google.api.http + shiguchi.api.graphql annotations → RouteDefinitions.
 *  5. Run StaticSerdeDetector (Tier 1) and DynamicSerializerFactory (Tier 2) pre-compilation.
 *  6. Write raw FileDescriptorSet bytes to a temp .pb file.
 *  7. Register the context in GrpcRouteRegistry.
 */
@Component
public class DynamicRouteLoader implements ServiceLoader {

    private static final Logger log = LoggerFactory.getLogger(DynamicRouteLoader.class);

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final Optional<ScgRouteRegistrar> scgRegistrar;
    private final DynamicProtoSerde serde;
    private final StaticSerdeDetector tier1;
    private final DynamicSerializerFactory tier2;
    private final GrpcChannelFactory channelFactory;
    private final dev.appiaworks.shiguchi.rest.MergeSpecResolver mergeSpecResolver;

    /** Services that failed @PostConstruct loading (e.g. self-connection before server is ready). */
    private final List<ServiceDefinition> pendingRetry = new ArrayList<>();

    /** Where load outcomes are recorded; a private instance until Spring injects the shared one. */
    private volatile ServiceLoadStatus loadStatus = new ServiceLoadStatus();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLoadStatus(ServiceLoadStatus loadStatus) {
        if (loadStatus != null) this.loadStatus = loadStatus;
    }

    /**
     * Resolved lazily, at retry time. Injecting the listeners eagerly forms a cycle: the OpenAPI
     * cache listener needs the OpenAPI builder, which {@code @DependsOn} this loader.
     */
    private volatile java.util.function.Supplier<List<SchemaReloadListener>> reloadListeners = List::of;

    @org.springframework.beans.factory.annotation.Autowired
    public DynamicRouteLoader(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            DynamicProtoSerde serde,
            StaticSerdeDetector tier1,
            DynamicSerializerFactory tier2,
            GrpcChannelFactory channelFactory,
            dev.appiaworks.shiguchi.rest.MergeSpecResolver mergeSpecResolver,
            org.springframework.beans.factory.ObjectProvider<SchemaReloadListener> reloadListeners) {
        this(properties, registry, scgRegistrar, serde, tier1, tier2, channelFactory, mergeSpecResolver);
        this.reloadListeners = () -> reloadListeners.orderedStream().toList();
    }

    /** Listeners supplied on demand; for tests and manual wiring. */
    public void setReloadListeners(java.util.function.Supplier<List<SchemaReloadListener>> reloadListeners) {
        this.reloadListeners = reloadListeners != null ? reloadListeners : List::of;
    }

    /** Convenience for tests and manual wiring: no reload listeners. */
    public DynamicRouteLoader(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            DynamicProtoSerde serde,
            StaticSerdeDetector tier1,
            DynamicSerializerFactory tier2,
            GrpcChannelFactory channelFactory,
            dev.appiaworks.shiguchi.rest.MergeSpecResolver mergeSpecResolver) {
        this.properties = properties;
        this.registry = registry;
        this.scgRegistrar = scgRegistrar;
        this.serde = serde;
        this.tier1 = tier1;
        this.tier2 = tier2;
        this.channelFactory = channelFactory;
        this.mergeSpecResolver = mergeSpecResolver;
    }

    @PostConstruct
    public void load() {
        for (ServiceDefinition svcDef : properties.getServices()) {
            GatewayMode mode = svcDef.getMode();
            if (mode != GatewayMode.DYNAMIC && mode != GatewayMode.PB_FILE) continue;
            try {
                ServiceEndpointContext ctx = buildContext(svcDef);
                registry.registerContext(ctx);
                scgRegistrar.ifPresent(r -> r.register(ctx));
                loadStatus.recordLoaded(svcDef.getName());
            } catch (Exception e) {
                // Typically a self-connection race: the embedded gRPC server isn't listening yet
                // when @PostConstruct fires. Retry after ApplicationReadyEvent when all servers are up.
                log.warn(
                        "Route load deferred for service '{}': {} — will retry after startup",
                        svcDef.getName(),
                        e.getMessage());
                loadStatus.recordFailure(svcDef.getName(), "startup", e);
                pendingRetry.add(svcDef);
            }
        }
    }

    /** Retries any services that failed during @PostConstruct (e.g. self-connection before server ready). */
    @EventListener(ApplicationReadyEvent.class)
    public void retryFailedOnReady() {
        if (pendingRetry.isEmpty()) return;
        log.info(
                "Retrying route load for {} deferred service(s) now that all servers are started", pendingRetry.size());
        boolean registeredAny = false;
        for (ServiceDefinition svcDef : pendingRetry) {
            try {
                ServiceEndpointContext ctx = buildContext(svcDef);
                registry.registerContext(ctx);
                scgRegistrar.ifPresent(r -> r.register(ctx));
                registeredAny = true;
                loadStatus.recordLoaded(svcDef.getName());
                log.info("Deferred route load succeeded for service '{}'", svcDef.getName());
            } catch (Exception e) {
                log.error("Failed to load routes for service '{}': {}", svcDef.getName(), e.getMessage(), e);
                loadStatus.recordFailure(svcDef.getName(), "ready-retry", e);
            }
        }
        pendingRetry.clear();
        // The generated documents were built at startup without these services; rebuild them.
        if (registeredAny) SchemaReloadListeners.notifyAll(reloadListeners.get(), log);
    }

    @Override
    public boolean supports(ServiceDefinition service) {
        return service.getMode() == GatewayMode.DYNAMIC || service.getMode() == GatewayMode.PB_FILE;
    }

    /**
     * A backend's schema as read over reflection: each service's descriptor set, in the order
     * listed, and — when it was read from one pinned instance — that instance and its version.
     */
    record ReflectedSchema(Map<String, FileDescriptorSet> services, String instance, String version) {
        /** The same digest {@link #buildContext} stores, over the same bytes in the same order. */
        byte[] hash() throws java.security.NoSuchAlgorithmException {
            FileDescriptorSet.Builder combined = FileDescriptorSet.newBuilder();
            services.values().forEach(fds -> combined.addAllFile(fds.getFileList()));
            return MessageDigest.getInstance("SHA-256").digest(combined.build().toByteArray());
        }
    }

    /**
     * Reads the schema over reflection. A {@code discovery-name} backend is read from the one
     * instance {@code versions.schema-from} picks, over a channel of its own, so every call of one
     * load — and every refresh — sees the same version (see {@link SchemaSourceSelector}); any
     * other backend is read over {@code channel}.
     */
    ReflectedSchema reflect(ServiceDefinition svcDef, ManagedChannel channel) throws Exception {
        var source = channelFactory.schemaSource(svcDef);
        ManagedChannel schemaChannel =
                source.map(s -> channelFactory.buildSchemaChannel(svcDef, s)).orElse(null);
        try {
            var reflectionClient = new GrpcReflectionClient(schemaChannel != null ? schemaChannel : channel);
            Map<String, FileDescriptorSet> services = new java.util.LinkedHashMap<>();
            for (String serviceName : reflectionClient.listServices()) {
                services.put(serviceName, reflectionClient.resolveService(serviceName));
            }
            return new ReflectedSchema(
                    services,
                    source.map(SchemaSourceSelector.SchemaSource::address).orElse(null),
                    source.map(SchemaSourceSelector.SchemaSource::version).orElse(null));
        } finally {
            if (schemaChannel != null) schemaChannel.shutdownNow();
        }
    }

    /**
     * The descriptor hash a reload of {@code svcDef} would store, read the same way a load reads
     * it — the refresh scheduler's change probe. Comparing anything narrower (one service's
     * descriptors) with the stored hash would differ for every backend exposing more than one
     * service, and reload it on every tick.
     */
    public byte[] probeHash(ServiceDefinition svcDef, ServiceEndpointContext current) throws Exception {
        return reflect(svcDef, current.getChannel()).hash();
    }

    /**
     * Builds a fully-populated ServiceEndpointContext for a service definition.
     * Called at startup and by GatewayRefreshScheduler on reload.
     */
    @Override
    public ServiceEndpointContext buildContext(ServiceDefinition svcDef) throws Exception {
        ManagedChannel channel = channelFactory.build(svcDef);
        try {
            return buildContext(svcDef, channel);
        } catch (Exception | Error e) {
            // A failed load (backend down, reflection refused, a bad descriptor) must not leave
            // its channel behind: the pending-load retrier builds a fresh one on every attempt,
            // and each abandoned channel would keep reconnecting in the background for good.
            channel.shutdownNow();
            throw e;
        }
    }

    private ServiceEndpointContext buildContext(ServiceDefinition svcDef, ManagedChannel channel) throws Exception {
        ServiceEndpointContext ctx = new ServiceEndpointContext(svcDef, channel);

        Map<String, FileDescriptor> allFileDescriptors = new HashMap<>();
        FileDescriptorSet.Builder combinedFdsBuilder = FileDescriptorSet.newBuilder();
        Map<String, ServiceDescriptor> serviceByFqn = new HashMap<>();
        Map<String, MethodDescriptor> methodByKey = new HashMap<>();

        if (svcDef.getMode() == GatewayMode.PB_FILE) {
            // Load descriptor graph from pre-compiled .pb file — no reflection needed
            String pbPath = svcDef.getPbDescriptorPath();
            if (pbPath == null || pbPath.isBlank()) {
                throw new IllegalStateException(
                        "Service '" + svcDef.getName() + "' has mode PB_FILE but no pbDescriptorPath configured");
            }
            FileDescriptorSet fds = FileDescriptorSet.parseFrom(
                    Files.readAllBytes(Path.of(pbPath)),
                    dev.appiaworks.shiguchi.reflection.GatewayExtensionRegistry.get());
            combinedFdsBuilder.addAllFile(fds.getFileList());
            Map<String, FileDescriptor> fileDescriptors = serde.buildFileDescriptors(fds);
            allFileDescriptors.putAll(fileDescriptors);
            // Documentation the .proto was written with, when protoc kept it
            // (--include_source_info). Reflection never sends source info and compiled stubs do
            // not retain it, so PB_FILE is the only mode where this is available at all.
            for (com.google.protobuf.DescriptorProtos.FileDescriptorProto fileProto : fds.getFileList()) {
                ctx.putProtoComments(dev.appiaworks.shiguchi.grpc.ProtoComments.of(fileProto));
            }
            for (FileDescriptor fd : fileDescriptors.values()) {
                for (ServiceDescriptor sd : fd.getServices()) {
                    serviceByFqn.put(sd.getFullName(), sd);
                    for (MethodDescriptor md : sd.getMethods()) {
                        methodByKey.put(sd.getFullName() + "/" + md.getName(), md);
                        registerMethod(ctx, sd, md, svcDef);
                    }
                }
            }
            log.info("Loaded descriptor from file '{}' for service '{}'", pbPath, svcDef.getName());
        } else {
            // DYNAMIC: discover via gRPC server reflection, from one pinned instance when the
            // backend is reached through discovery.
            ReflectedSchema reflected = reflect(svcDef, channel);
            ctx.setSchemaSource(reflected.version(), reflected.instance());
            log.info(
                    "Discovered {} services on {}{}",
                    reflected.services().size(),
                    reflected.instance() != null ? reflected.instance() : svcDef.getHost() + ":" + svcDef.getPort(),
                    reflected.version() != null ? " (version " + reflected.version() + ")" : "");

            for (var entry : reflected.services().entrySet()) {
                String serviceName = entry.getKey();
                FileDescriptorSet fds = entry.getValue();
                combinedFdsBuilder.addAllFile(fds.getFileList());
                Map<String, FileDescriptor> fileDescriptors = serde.buildFileDescriptors(fds);
                allFileDescriptors.putAll(fileDescriptors);

                for (FileDescriptor fd : fileDescriptors.values()) {
                    for (ServiceDescriptor sd : fd.getServices()) {
                        if (!sd.getFullName().equals(serviceName)) continue;
                        serviceByFqn.put(sd.getFullName(), sd);
                        for (MethodDescriptor md : sd.getMethods()) {
                            methodByKey.put(sd.getFullName() + "/" + md.getName(), md);
                            registerMethod(ctx, sd, md, svcDef);
                        }
                    }
                }
            }
        }

        FileDescriptorSet combinedFds = combinedFdsBuilder.build();
        ctx.setFileDescriptors(allFileDescriptors);
        ctx.setRawDescriptorSet(combinedFds);
        ctx.setTypeRegistry(serde.buildTypeRegistry(allFileDescriptors.values()));
        ctx.setServiceByFqn(serviceByFqn);
        ctx.setMethodByKey(methodByKey);

        byte[] hash = MessageDigest.getInstance("SHA-256").digest(combinedFds.toByteArray());
        ctx.setDescriptorHash(hash);

        Path pbFile = Files.createTempFile("gsg-" + svcDef.getName() + "-", ".pb");
        Files.write(pbFile, combinedFds.toByteArray());
        ctx.setPbFilePath(pbFile);

        tier1.scan(ctx);
        tier2.precompile(ctx);

        channelFactory.applyRetryExclusions(ctx, svcDef);

        log.info("Loaded {} routes for service '{}'", ctx.getRoutes().size(), svcDef.getName());
        return ctx;
    }

    private void registerMethod(
            ServiceEndpointContext ctx, ServiceDescriptor sd, MethodDescriptor md, ServiceDefinition svcDef) {

        if (!svcDef.isMethodExposed(sd.getFullName(), md.getName())) {
            log.debug("Method {}/{} hidden by include/exclude-methods filters", sd.getFullName(), md.getName());
            return;
        }

        boolean globalRest = properties.getRest().isEnabled();
        boolean globalGraphQl = properties.getGraphql().isEnabled();

        var builder = RouteDefinition.builder(
                        sd.getFullName(), md.getName(), DynamicProtoSerde.resolveMethodType(md), svcDef.getTarget())
                // Whether repeating this call is safe, which is what gates retries for it.
                .idempotencyLevel(RetryServiceConfig.idempotencyOf(md.getOptions()));

        List<HttpRuleBindings.Binding> bindings = List.of();
        if (svcDef.isRestEnabled(globalRest)) {
            HttpRule httpRule = md.getOptions().getExtension(AnnotationsProto.http);
            bindings = HttpRuleBindings.of(httpRule);
            if (!bindings.isEmpty() && md.isClientStreaming() && md.isServerStreaming()) {
                // One HTTP request and one response cannot carry two independent streams: the
                // route would accept calls and then fail every one. Keep the method off REST.
                log.warn(
                        "{}/{} is bidirectional streaming; its google.api.http binding is ignored "
                                + "(REST cannot carry a bidi stream). Call it over gRPC.",
                        sd.getFullName(),
                        md.getName());
                bindings = List.of();
            }
            // No rule: with rest.auto-mapping the method still gets POST /{package.Service}/{Method}.
            // Not a bidi method, which one HTTP request and response cannot carry.
            if (bindings.isEmpty()
                    && svcDef.isAutoMapping(properties.getRest().isAutoMapping())
                    && DynamicProtoSerde.resolveMethodType(md) != io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING) {
                bindings = List.of(HttpRuleBindings.autoMapped(sd.getFullName(), md.getName()));
            }
            if (!bindings.isEmpty()) {
                HttpRuleBindings.Binding primary = bindings.get(0);
                builder.httpBinding(primary.method(), prefixed(svcDef, primary.path()), primary.body())
                        .httpResponseBody(primary.responseBody());
            }
        }

        // Access rules gate every surface, so they are read whether or not GraphQL is on for the
        // service and whether or not the annotation names a field.
        ProtoAccessRules.apply(builder, md);

        if (svcDef.isGraphqlEnabled(globalGraphQl)) {
            applyGraphQlBinding(builder, sd, md, ctx.protoComment(sd.getFullName() + "." + md.getName()));
        }

        RouteDefinition route = builder.build();
        if (!route.hasRestBinding() && !route.hasGraphQlBinding()) return;

        // A merge is declared against the finished route (it keys off the path or GraphQL name),
        // so resolve it here and rebuild rather than threading a half-built route through.
        var merge = mergeSpecResolver.resolve(route, ctx);
        if (merge.isPresent()) {
            route = builder.mergeSpec(merge.get()).build();
        }
        ctx.addRoute(route);

        // additional_bindings: one more REST route per entry, same RPC, same auth and hints,
        // no GraphQL field of its own and never a merge.
        for (HttpRuleBindings.Binding extra : bindings.subList(Math.min(1, bindings.size()), bindings.size())) {
            ctx.addRoute(builder.withoutGraphQl()
                    .mergeSpec(null)
                    .httpBinding(extra.method(), prefixed(svcDef, extra.path()), extra.body())
                    .httpResponseBody(extra.responseBody())
                    .build());
        }
    }

    private static String prefixed(ServiceDefinition svcDef, String path) {
        if (svcDef.getPathPrefix() != null && !svcDef.getPathPrefix().isBlank()) {
            return svcDef.getPathPrefix() + path;
        }
        return path;
    }

    private static String inferLoaderIdsField(Descriptor inputType) {
        for (FieldDescriptor f : inputType.getFields()) {
            if (f.isRepeated() && f.getType() != FieldDescriptor.Type.MESSAGE) return f.getName();
        }
        return "";
    }

    /**
     * Reads the {@code (shiguchi.api.graphql)} annotation from the method descriptor and populates the
     * builder with name, type, pluck, description, and other schema hints.
     * Falls back to convention-based naming when the annotation is absent or has no name.
     */
    private static void applyGraphQlBinding(
            RouteDefinition.Builder builder, ServiceDescriptor sd, MethodDescriptor md, String protoComment) {

        GraphQlRule annotation = md.getOptions().getExtension(GraphQlProto.graphql);

        // The comment above the rpc, for every method — a proto with no (shiguchi.api.graphql) at all is
        // the common case, and it still has documentation worth carrying. An annotation that sets
        // a description overwrites this below, because someone wrote that for this surface
        // specifically.
        if (protoComment != null) builder.description(protoComment);

        if (annotation != null && !annotation.getName().isEmpty()) {
            RouteDefinition.GraphQlOperationType opType = switch (annotation.getType()) {
                case MUTATION -> RouteDefinition.GraphQlOperationType.MUTATION;
                case SUBSCRIPTION -> RouteDefinition.GraphQlOperationType.SUBSCRIPTION;
                case RESOLVER -> RouteDefinition.GraphQlOperationType.RESOLVER;
                default -> RouteDefinition.GraphQlOperationType.QUERY;
            };
            builder.graphQl(annotation.getName(), opType);

            if (!annotation.getDescription().isEmpty()) builder.description(annotation.getDescription());
            if (!annotation.getDeprecationReason().isEmpty())
                builder.deprecationReason(annotation.getDeprecationReason());
            if (!annotation.getParentType().isEmpty()) builder.parentType(annotation.getParentType());

            // Resolve response pluck to JSON (camelCase) field name for accurate response extraction
            if (!annotation.getPluck().isEmpty()) {
                String pluckFieldName = annotation.getPluck();
                var pluckedField = md.getOutputType().findFieldByName(pluckFieldName);
                builder.responsePluck(pluckedField != null ? pluckedField.getJsonName() : pluckFieldName);
            }
            if (!annotation.getArgFieldsList().isEmpty())
                builder.requestPlucks(annotation.getArgFieldsList().stream()
                        .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());

            if (!annotation.getLoaderFor().isEmpty()) {
                String loaderIdsField = annotation.getLoaderIdsField().isEmpty()
                        ? inferLoaderIdsField(md.getInputType())
                        : annotation.getLoaderIdsField();
                builder.loaderFor(annotation.getLoaderFor(), loaderIdsField, annotation.getLoaderResultField());
            }

        } else {
            // Convention-based fallback: derive name and type from the RPC method name
            String name = md.getName();
            String lower = name.toLowerCase();
            RouteDefinition.GraphQlOperationType opType = (lower.startsWith("create")
                            || lower.startsWith("update")
                            || lower.startsWith("delete")
                            || lower.startsWith("upsert"))
                    ? RouteDefinition.GraphQlOperationType.MUTATION
                    : RouteDefinition.GraphQlOperationType.QUERY;
            builder.graphQl(StaticRouteLoader.graphQlFieldName(sd.getName(), name), opType);
        }
    }
}
