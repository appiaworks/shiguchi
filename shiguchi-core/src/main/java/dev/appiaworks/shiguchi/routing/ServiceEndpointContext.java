package dev.appiaworks.shiguchi.routing;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.ManagedChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached per-service state: channel, proto descriptors, serde instances, route indexes.
 *
 * Built once by DynamicRouteLoader at startup (or by GatewayRefreshScheduler on reload).
 * All fields are set during the build then read-only — safe for concurrent access. The only
 * fields written afterwards are descriptorHash, set atomically by the refresh scheduler, and the
 * channel, swapped once by {@link #replaceChannel} before the context is published.
 */
public class ServiceEndpointContext {

    private final ServiceDefinition config;

    /**
     * Null for a {@code REST_DOWNSTREAM} context, which has no gRPC backend.
     *
     * <p>Not final only because of {@link #replaceChannel}, which swaps in a retry-aware channel
     * during the build, before this context is published to the registry. It is never reassigned
     * once requests can see it.
     */
    private volatile ManagedChannel channel;

    /**
     * Raw OpenAPI document of a {@code REST_DOWNSTREAM} backend, exactly as fetched, plus its
     * media type. Kept as bytes so this core class stays free of OpenAPI model types; the openapi
     * module re-parses it when it merges the consolidated spec. Null for gRPC contexts.
     */
    private byte[] rawDownstreamSpec;

    private String downstreamSpecMediaType;

    /** {@code REST_DOWNSTREAM}: synthetic method name → how to call it over HTTP. Empty for gRPC contexts. */
    private final Map<String, HttpOperationBinding> httpBindings = new ConcurrentHashMap<>();

    /**
     * Documentation read out of the backend's {@code .proto} comments, keyed by fully-qualified
     * name ({@code pkg.Service.Method}, {@code pkg.Message.field}).
     *
     * <p>Populated only in {@code PB_FILE} mode with a descriptor built by
     * {@code protoc --include_source_info}: reflection does not send source info and compiled
     * stubs do not retain it, so this is empty for every other mode and callers fall back to the
     * annotation-supplied description.
     */
    private final Map<String, String> protoComments = new ConcurrentHashMap<>();

    /**
     * {@code REST_DOWNSTREAM}: synthetic enum full name → (proto value name → the string the backend
     * uses), so values can be translated at the HTTP boundary. Empty for gRPC contexts.
     */
    private Map<String, Map<String, String>> enumValueOriginals = Collections.emptyMap();

    // Proto descriptor state (populated from gRPC reflection)
    private Map<String, FileDescriptor> fileDescriptors = Collections.emptyMap();
    private FileDescriptorSet rawDescriptorSet;
    private JsonFormat.TypeRegistry typeRegistry;

    // Lookup indexes — rebuilt atomically on refresh
    private Map<String, ServiceDescriptor> serviceByFqn = Collections.emptyMap();
    private Map<String, MethodDescriptor> methodByKey = Collections.emptyMap(); // "ServiceFqn/methodName"

    // Tier 2 serde cache — populated by DynamicSerializerFactory.precompile()
    private final Map<com.google.protobuf.Descriptors.Descriptor, AbstractDynamicSerializer> serializerCache =
            new ConcurrentHashMap<>();

    // Routes registered from this service's proto annotations
    private final List<RouteDefinition> routes = new ArrayList<>();

    // .pb file path written for potential SCG JsonToGrpc integration
    private Path pbFilePath;

    // SHA-256 hash of the raw FileDescriptorSet bytes; used by refresh scheduler for change detection
    private volatile byte[] descriptorHash;

    private final Instant loadedAt = Instant.now();

    public ServiceEndpointContext(ServiceDefinition config, ManagedChannel channel) {
        this.config = config;
        this.channel = channel;
    }

    // -------------------------------------------------------------------------
    // Mutation methods — called only during build/reload, not at request time
    // -------------------------------------------------------------------------

    /**
     * Swaps in a different channel during the build and returns the previous one for the caller to
     * shut down.
     *
     * <p>Used once, by the route loaders: a service's retry policy has to name the methods that
     * must not be retried, and those are only known after the descriptors have been read — which
     * in {@code DYNAMIC} mode needs a channel in the first place. The first channel serves
     * descriptor loading, this one serves requests.
     */
    public ManagedChannel replaceChannel(ManagedChannel replacement) {
        ManagedChannel previous = this.channel;
        this.channel = replacement;
        return previous;
    }

    public void setFileDescriptors(Map<String, FileDescriptor> fileDescriptors) {
        this.fileDescriptors = Collections.unmodifiableMap(fileDescriptors);
    }

    public void setRawDescriptorSet(FileDescriptorSet rawDescriptorSet) {
        this.rawDescriptorSet = rawDescriptorSet;
    }

    public void setTypeRegistry(JsonFormat.TypeRegistry typeRegistry) {
        this.typeRegistry = typeRegistry;
    }

    public void setServiceByFqn(Map<String, ServiceDescriptor> serviceByFqn) {
        this.serviceByFqn = Collections.unmodifiableMap(serviceByFqn);
    }

    public void setMethodByKey(Map<String, MethodDescriptor> methodByKey) {
        this.methodByKey = Collections.unmodifiableMap(methodByKey);
    }

    public void setPbFilePath(Path pbFilePath) {
        this.pbFilePath = pbFilePath;
    }

    private volatile String schemaVersion;
    private volatile String schemaInstance;

    /**
     * Where the schema was read from, for a backend with several instances: the instance's address
     * and the version it published (null when it published none).
     */
    public void setSchemaSource(String version, String instance) {
        this.schemaVersion = version;
        this.schemaInstance = instance;
    }

    /** The version the schema was read from; null when unknown or not pinned. */
    public String getSchemaVersion() {
        return schemaVersion;
    }

    /** The instance ({@code host:port}) the schema was read from; null when not pinned. */
    public String getSchemaInstance() {
        return schemaInstance;
    }

    public void setDescriptorHash(byte[] hash) {
        this.descriptorHash = Arrays.copyOf(hash, hash.length);
    }

    /** Stores the fetched downstream OpenAPI document; {@code mediaType} is {@code application/json} or {@code application/yaml}. */
    public void setRawDownstreamSpec(byte[] raw, String mediaType) {
        this.rawDownstreamSpec = raw != null ? Arrays.copyOf(raw, raw.length) : null;
        this.downstreamSpecMediaType = mediaType;
    }

    public void addRoute(RouteDefinition route) {
        routes.add(route);
    }

    public void putSerializer(
            com.google.protobuf.Descriptors.Descriptor descriptor, AbstractDynamicSerializer serializer) {
        serializerCache.put(descriptor, serializer);
    }

    // -------------------------------------------------------------------------
    // Query methods — used at request time
    // -------------------------------------------------------------------------

    public Optional<MethodDescriptor> lookupMethod(String serviceFqn, String methodName) {
        return Optional.ofNullable(methodByKey.get(serviceFqn + "/" + methodName));
    }

    public Optional<AbstractDynamicSerializer> serializerFor(com.google.protobuf.Descriptors.Descriptor descriptor) {
        return Optional.ofNullable(serializerCache.get(descriptor));
    }

    public String target() {
        return config.getTarget();
    }

    public ServiceDefinition getConfig() {
        return config;
    }

    private volatile dev.appiaworks.protobuf.extras.serde.JsonPrintOptions printOptions;

    /**
     * How this service's REST responses are printed: {@code shiguchi.json.print} with the
     * service's {@code json-print} overrides. Resolved on first use, after the global options are
     * installed, and fixed for the life of this context (a reload builds a new one).
     */
    public dev.appiaworks.protobuf.extras.serde.JsonPrintOptions getPrintOptions() {
        var options = printOptions;
        if (options == null) {
            options = dev.appiaworks.shiguchi.serde.GatewayJsonSettings.printOptionsFor(config);
            printOptions = options;
        }
        return options;
    }

    public ManagedChannel getChannel() {
        return channel;
    }

    public Map<String, FileDescriptor> getFileDescriptors() {
        return fileDescriptors;
    }

    public FileDescriptorSet getRawDescriptorSet() {
        return rawDescriptorSet;
    }

    public JsonFormat.TypeRegistry getTypeRegistry() {
        return typeRegistry;
    }

    public Map<String, ServiceDescriptor> getServiceByFqn() {
        return serviceByFqn;
    }

    public List<RouteDefinition> getRoutes() {
        return Collections.unmodifiableList(routes);
    }

    public Path getPbFilePath() {
        return pbFilePath;
    }

    public byte[] getDescriptorHash() {
        return descriptorHash;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    /** Records the documentation read from a descriptor's source info. */
    public void putProtoComments(Map<String, String> comments) {
        if (comments != null) protoComments.putAll(comments);
    }

    /**
     * The proto comment documenting {@code fullName}, or null when there is none.
     *
     * @param fullName {@code pkg.Service}, {@code pkg.Service.Method}, {@code pkg.Message} or
     *     {@code pkg.Message.field}
     */
    public String protoComment(String fullName) {
        return protoComments.get(fullName);
    }

    public void putHttpBinding(String methodName, HttpOperationBinding binding) {
        httpBindings.put(methodName, binding);
    }

    public void setEnumValueOriginals(Map<String, Map<String, String>> enumValueOriginals) {
        this.enumValueOriginals =
                enumValueOriginals != null ? Collections.unmodifiableMap(enumValueOriginals) : Collections.emptyMap();
    }

    public Map<String, Map<String, String>> getEnumValueOriginals() {
        return enumValueOriginals;
    }

    public Optional<HttpOperationBinding> httpBindingFor(String methodName) {
        return Optional.ofNullable(httpBindings.get(methodName));
    }

    /**
     * True for a {@code REST_DOWNSTREAM} context: no channel, routes are HTTP passthroughs, and any
     * descriptors present are synthetic ones derived from the OpenAPI document for the GraphQL surface.
     */
    public boolean isRestDownstream() {
        return rawDownstreamSpec != null;
    }

    public byte[] getRawDownstreamSpec() {
        return rawDownstreamSpec != null ? Arrays.copyOf(rawDownstreamSpec, rawDownstreamSpec.length) : null;
    }

    public String getDownstreamSpecMediaType() {
        return downstreamSpecMediaType;
    }
}
