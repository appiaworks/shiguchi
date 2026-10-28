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
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Populates GrpcRouteRegistry at startup for all STATIC mode services.
 *
 * Unlike DynamicRouteLoader (which calls gRPC server reflection at startup),
 * this loader reads proto descriptors from compiled Java stubs on the classpath —
 * no live gRPC connection is needed to discover routes.
 *
 * For each STATIC service:
 *  1. For each stub class in staticServiceClasses:
 *     a. Load the class via Class.forName().
 *     b. Call getServiceDescriptor() to get the io.grpc.ServiceDescriptor.
 *     c. Cast getSchemaDescriptor() to ProtoServiceDescriptorSupplier to obtain
 *        the com.google.protobuf.Descriptors.ServiceDescriptor with full field info.
 *     d. Recursively collect transitive FileDescriptor dependencies.
 *  2. Build a ServiceEndpointContext identical to the DYNAMIC path (channel, type
 *     registry, route index, serde tiers) — the channel is still needed at request time.
 *  3. Register the context in GrpcRouteRegistry.
 */
@Component
public class StaticRouteLoader implements ServiceLoader {

    private static final Logger log = LoggerFactory.getLogger(StaticRouteLoader.class);

    private final GatewayProperties properties;
    private final GrpcRouteRegistry registry;
    private final Optional<ScgRouteRegistrar> scgRegistrar;
    private final DynamicProtoSerde serde;
    private final StaticSerdeDetector tier1;
    private final DynamicSerializerFactory tier2;
    private final GrpcChannelFactory channelFactory;
    private final dev.appiaworks.shiguchi.rest.MergeSpecResolver mergeSpecResolver;
    private final ClasspathServiceScanner classpathScanner;
    private final SpringBeanServiceDiscovery springBeanDiscovery;

    /** Where load outcomes are recorded; a private instance until Spring injects the shared one. */
    private volatile ServiceLoadStatus loadStatus = new ServiceLoadStatus();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLoadStatus(ServiceLoadStatus loadStatus) {
        if (loadStatus != null) this.loadStatus = loadStatus;
    }

    public StaticRouteLoader(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            Optional<ScgRouteRegistrar> scgRegistrar,
            DynamicProtoSerde serde,
            StaticSerdeDetector tier1,
            DynamicSerializerFactory tier2,
            GrpcChannelFactory channelFactory,
            ClasspathServiceScanner classpathScanner,
            SpringBeanServiceDiscovery springBeanDiscovery,
            dev.appiaworks.shiguchi.rest.MergeSpecResolver mergeSpecResolver) {
        this.properties = properties;
        this.registry = registry;
        this.scgRegistrar = scgRegistrar;
        this.serde = serde;
        this.tier1 = tier1;
        this.tier2 = tier2;
        this.channelFactory = channelFactory;
        this.mergeSpecResolver = mergeSpecResolver;
        this.classpathScanner = classpathScanner;
        this.springBeanDiscovery = springBeanDiscovery;
    }

    @PostConstruct
    public void load() {
        for (ServiceDefinition svcDef : properties.getServices()) {
            if (svcDef.getMode() != GatewayMode.STATIC) continue;
            try {
                ServiceEndpointContext ctx = buildContext(svcDef);
                registry.registerContext(ctx);
                scgRegistrar.ifPresent(r -> r.register(ctx));
                loadStatus.recordLoaded(svcDef.getName());
            } catch (Exception e) {
                log.error("Failed to load static routes for service '{}': {}", svcDef.getName(), e.getMessage(), e);
                loadStatus.recordFailure(svcDef.getName(), "startup", e);
            }
        }
    }

    @Override
    public boolean supports(ServiceDefinition service) {
        return service.getMode() == GatewayMode.STATIC;
    }

    @Override
    public ServiceEndpointContext buildContext(ServiceDefinition svcDef) throws Exception {
        classpathScanner.populateIfEnabled(svcDef);
        springBeanDiscovery.populateIfEnabled(svcDef);

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
        Map<String, ServiceDescriptor> serviceByFqn = new HashMap<>();
        Map<String, MethodDescriptor> methodByKey = new HashMap<>();

        for (String stubClassName : svcDef.getStaticServiceClasses()) {
            try {
                ServiceDescriptor protoSd = resolveProtoServiceDescriptor(stubClassName);
                if (protoSd == null) continue;

                collectFileDescriptors(protoSd.getFile(), allFileDescriptors);
                serviceByFqn.put(protoSd.getFullName(), protoSd);

                for (MethodDescriptor md : protoSd.getMethods()) {
                    methodByKey.put(protoSd.getFullName() + "/" + md.getName(), md);
                    registerMethod(ctx, protoSd, md, svcDef);
                }

                log.debug("Loaded static service '{}' from stub {}", protoSd.getFullName(), stubClassName);
            } catch (ClassNotFoundException e) {
                log.warn(
                        "Static stub class '{}' not found on classpath for service '{}'; skipping",
                        stubClassName,
                        svcDef.getName());
            } catch (Exception e) {
                log.warn("Failed to load static service from stub '{}': {}", stubClassName, e.getMessage());
            }
        }

        FileDescriptorSet.Builder fdsBuilder = FileDescriptorSet.newBuilder();
        allFileDescriptors.values().forEach(fd -> fdsBuilder.addFile(fd.toProto()));
        FileDescriptorSet combinedFds = fdsBuilder.build();

        ctx.setFileDescriptors(allFileDescriptors);
        ctx.setRawDescriptorSet(combinedFds);
        ctx.setTypeRegistry(serde.buildTypeRegistry(allFileDescriptors.values()));
        ctx.setServiceByFqn(serviceByFqn);
        ctx.setMethodByKey(methodByKey);

        byte[] hash = MessageDigest.getInstance("SHA-256").digest(combinedFds.toByteArray());
        ctx.setDescriptorHash(hash);

        Path pbFile = Files.createTempFile("gsg-" + svcDef.getName() + "-static-", ".pb");
        Files.write(pbFile, combinedFds.toByteArray());
        ctx.setPbFilePath(pbFile);

        tier1.scan(ctx);
        tier2.precompile(ctx);

        channelFactory.applyRetryExclusions(ctx, svcDef);

        log.info("Loaded {} static routes for service '{}'", ctx.getRoutes().size(), svcDef.getName());
        return ctx;
    }

    /**
     * Extracts the protobuf ServiceDescriptor from a gRPC generated stub class.
     */
    private static ServiceDescriptor resolveProtoServiceDescriptor(String stubClassName) throws Exception {
        Class<?> stubClass = Class.forName(stubClassName);
        io.grpc.ServiceDescriptor grpcSd = (io.grpc.ServiceDescriptor)
                stubClass.getMethod("getServiceDescriptor").invoke(null);

        Object schemaDesc = grpcSd.getSchemaDescriptor();
        if (!(schemaDesc instanceof ProtoServiceDescriptorSupplier supplier)) {
            log.warn(
                    "Stub {} schema descriptor does not implement ProtoServiceDescriptorSupplier; skipping",
                    stubClassName);
            return null;
        }

        ServiceDescriptor protoSd = supplier.getServiceDescriptor();
        if (protoSd == null) {
            log.warn("ProtoServiceDescriptorSupplier returned null for {}; skipping", stubClassName);
        }
        return protoSd;
    }

    private static String inferLoaderIdsField(Descriptor inputType) {
        for (FieldDescriptor f : inputType.getFields()) {
            if (f.isRepeated() && f.getType() != FieldDescriptor.Type.MESSAGE) return f.getName();
        }
        return "";
    }

    /**
     * Builds a unique GraphQL field name: servicePrefix + MethodName.
     * e.g. ShopCategoryService.GetById → shopCategoryGetById
     */
    static String graphQlFieldName(String serviceSimpleName, String methodName) {
        String stripped = serviceSimpleName.endsWith("Service")
                ? serviceSimpleName.substring(0, serviceSimpleName.length() - "Service".length())
                : serviceSimpleName;
        String prefix = Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
        return prefix + Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
    }

    private static void collectFileDescriptors(FileDescriptor fd, Map<String, FileDescriptor> collected) {
        if (collected.containsKey(fd.getName())) return;
        collected.put(fd.getName(), fd);
        for (FileDescriptor dep : fd.getDependencies()) {
            collectFileDescriptors(dep, collected);
        }
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
            applyGraphQlBinding(builder, sd, md);
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

    /**
     * Reads the {@code (shiguchi.api.graphql)} annotation and populates the builder.
     * Falls back to convention-based naming when the annotation is absent or has no name.
     */
    private static void applyGraphQlBinding(
            RouteDefinition.Builder builder, ServiceDescriptor sd, MethodDescriptor md) {

        GraphQlRule annotation = md.getOptions().getExtension(GraphQlProto.graphql);

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
            String name = md.getName();
            String lower = name.toLowerCase();
            RouteDefinition.GraphQlOperationType opType = (lower.startsWith("create")
                            || lower.startsWith("update")
                            || lower.startsWith("delete")
                            || lower.startsWith("upsert"))
                    ? RouteDefinition.GraphQlOperationType.MUTATION
                    : RouteDefinition.GraphQlOperationType.QUERY;
            builder.graphQl(graphQlFieldName(sd.getName(), name), opType);
        }
    }
}
