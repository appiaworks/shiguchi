package dev.appiaworks.shiguchi.grpc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicMessageMarshaller;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Caches two kinds of per-call descriptors that are expensive to rebuild on every request:
 *
 * <p><b>io.grpc.MethodDescriptor&lt;DynamicMessage, DynamicMessage&gt;</b> — built from a proto
 * {@link MethodDescriptor} + {@link ServiceDescriptor}; keyed by
 * {@code ServiceEndpointContext × "serviceFqn/methodName"}. A Caffeine cache with weak keys means
 * entries for a replaced context (after a schema refresh) are eligible for GC as soon as in-flight
 * requests that still hold the old context complete. {@code Cache.get()} is internally lock-striped,
 * so concurrent lookups for different contexts proceed without serialisation.
 *
 * <p><b>DynamicMessageMarshaller</b> — stateless, keyed by proto {@link Descriptor} identity.
 * Descriptor objects are unique singletons per proto type within a classloader, so identity
 * equality is correct and safe.
 */
@Component
public class GrpcDescriptorCache {

    // weakKeys() → identity equality (correct for ServiceEndpointContext) + automatic eviction
    // when a stale context is GC'd after a service refresh.
    private final Cache<
                    ServiceEndpointContext,
                    ConcurrentHashMap<String, io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>>>
            contextCache = Caffeine.newBuilder().weakKeys().build();

    private final ConcurrentHashMap<Descriptor, DynamicMessageMarshaller> marshallerCache = new ConcurrentHashMap<>();

    /**
     * Lookups and the subset of them that had to build a descriptor.
     *
     * <p>Counted here rather than through Caffeine's own {@code recordStats}: the outer Caffeine
     * cache is per context and almost always hits, while the lookup that matters is the inner map
     * keyed by method. Steady-state this should be all hits — a miss rate that stays above zero
     * means descriptors are being rebuilt, which is a refresh loop or a leak of contexts.
     */
    private final java.util.concurrent.atomic.LongAdder lookups = new java.util.concurrent.atomic.LongAdder();

    private final java.util.concurrent.atomic.LongAdder misses = new java.util.concurrent.atomic.LongAdder();

    /** Total descriptor lookups since startup. */
    public long lookupCount() {
        return lookups.sum();
    }

    /** Lookups that had to build the descriptor. */
    public long missCount() {
        return misses.sum();
    }

    /** Records one lookup; {@code built} says whether it was a miss. */
    private <T> T counted(java.util.function.Supplier<T> lookup) {
        lookups.increment();
        return lookup.get();
    }

    /**
     * Returns the cached {@code io.grpc.MethodDescriptor} for the given context and proto method,
     * building and caching it on first access.
     */
    public io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> resolve(
            ServiceEndpointContext ctx,
            ServiceDescriptor sd,
            MethodDescriptor md,
            io.grpc.MethodDescriptor.MethodType type) {

        ConcurrentHashMap<String, io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>> byMethod =
                contextCache.get(ctx, k -> new ConcurrentHashMap<>());
        return counted(() -> byMethod.computeIfAbsent(sd.getFullName() + "/" + md.getName(), k -> {
            misses.increment();
            return build(sd, md, type);
        }));
    }

    private io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> build(
            ServiceDescriptor sd, MethodDescriptor md, io.grpc.MethodDescriptor.MethodType type) {

        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(type)
                .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(sd.getFullName(), md.getName()))
                .setRequestMarshaller(marshallerFor(md.getInputType()))
                .setResponseMarshaller(marshallerFor(md.getOutputType()))
                .build();
    }

    /**
     * Tier 1 variant of {@link #resolve}: the response marshaller is the <em>generated</em>
     * parser ({@code ProtoUtils.marshaller(defaultInstance)}) so responses arrive as typed
     * messages, ready for the Tier 1 typed writer. Requests still use DynamicMessage.
     * When {@code responseDefault} is null this degrades to the dynamic method descriptor.
     *
     * <p>The unchecked casts are client-side safe: a client never calls {@code stream()} on
     * the response marshaller nor {@code parse()} on the request marshaller.
     */
    @SuppressWarnings("unchecked")
    public io.grpc.MethodDescriptor<DynamicMessage, com.google.protobuf.Message> resolveResponseAsMessage(
            ServiceEndpointContext ctx,
            ServiceDescriptor sd,
            MethodDescriptor md,
            io.grpc.MethodDescriptor.MethodType type,
            com.google.protobuf.Message responseDefault) {

        if (responseDefault == null) {
            return (io.grpc.MethodDescriptor<DynamicMessage, com.google.protobuf.Message>)
                    (io.grpc.MethodDescriptor<?, ?>) resolve(ctx, sd, md, type);
        }

        ConcurrentHashMap<String, io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>> byMethod =
                contextCache.get(ctx, k -> new ConcurrentHashMap<>());
        var typed = byMethod.computeIfAbsent(
                sd.getFullName() + "/" + md.getName() + "#t1",
                k -> (io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>) (io.grpc.MethodDescriptor<?, ?>)
                        io.grpc.MethodDescriptor.<DynamicMessage, com.google.protobuf.Message>newBuilder()
                                .setType(type)
                                .setFullMethodName(
                                        io.grpc.MethodDescriptor.generateFullMethodName(sd.getFullName(), md.getName()))
                                .setRequestMarshaller(marshallerFor(md.getInputType()))
                                .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(responseDefault))
                                .build());
        return (io.grpc.MethodDescriptor<DynamicMessage, com.google.protobuf.Message>)
                (io.grpc.MethodDescriptor<?, ?>) typed;
    }

    /**
     * Tier 0 variant of {@link #resolve}: the response marshaller hands back the raw wire
     * bytes untouched, for direct wire→JSON transcoding. Requests still use DynamicMessage.
     * Same client-side cast-safety argument as {@link #resolveResponseAsMessage}.
     */
    @SuppressWarnings("unchecked")
    public io.grpc.MethodDescriptor<DynamicMessage, byte[]> resolveRawResponse(
            ServiceEndpointContext ctx,
            ServiceDescriptor sd,
            MethodDescriptor md,
            io.grpc.MethodDescriptor.MethodType type) {

        ConcurrentHashMap<String, io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>> byMethod =
                contextCache.get(ctx, k -> new ConcurrentHashMap<>());
        var raw = byMethod.computeIfAbsent(
                sd.getFullName() + "/" + md.getName() + "#t0",
                k -> (io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>)
                        (io.grpc.MethodDescriptor<?, ?>) io.grpc.MethodDescriptor.<DynamicMessage, byte[]>newBuilder()
                                .setType(type)
                                .setFullMethodName(
                                        io.grpc.MethodDescriptor.generateFullMethodName(sd.getFullName(), md.getName()))
                                .setRequestMarshaller(marshallerFor(md.getInputType()))
                                .setResponseMarshaller(RawBytesMarshaller.INSTANCE)
                                .build());
        return (io.grpc.MethodDescriptor<DynamicMessage, byte[]>) (io.grpc.MethodDescriptor<?, ?>) raw;
    }

    /**
     * {@link #resolveRawResponse} with the request raw too: the caller hands over the request
     * message's wire bytes (encoded straight from the HTTP request) and gets the response's
     * back, no message object on either side.
     */
    @SuppressWarnings("unchecked")
    public io.grpc.MethodDescriptor<byte[], byte[]> resolveRaw(
            ServiceEndpointContext ctx,
            ServiceDescriptor sd,
            MethodDescriptor md,
            io.grpc.MethodDescriptor.MethodType type) {

        ConcurrentHashMap<String, io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>> byMethod =
                contextCache.get(ctx, k -> new ConcurrentHashMap<>());
        var raw = byMethod.computeIfAbsent(
                sd.getFullName() + "/" + md.getName() + "#raw-" + type,
                k -> (io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage>)
                        (io.grpc.MethodDescriptor<?, ?>) io.grpc.MethodDescriptor.<byte[], byte[]>newBuilder()
                                .setType(type)
                                .setFullMethodName(
                                        io.grpc.MethodDescriptor.generateFullMethodName(sd.getFullName(), md.getName()))
                                .setRequestMarshaller(RawBytesMarshaller.INSTANCE)
                                .setResponseMarshaller(RawBytesMarshaller.INSTANCE)
                                .build());
        return (io.grpc.MethodDescriptor<byte[], byte[]>) (io.grpc.MethodDescriptor<?, ?>) raw;
    }

    /** Client-side response marshaller that captures the wire bytes without parsing them. */
    enum RawBytesMarshaller implements io.grpc.MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public java.io.InputStream stream(byte[] value) {
            return new java.io.ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(java.io.InputStream stream) {
            try {
                // gRPC hands over a KnownLength stream: available() is the whole message. Reading
                // exactly that many bytes allocates the array once; readAllBytes would first fill
                // a 16 KiB scratch buffer per message and then copy.
                int size = stream.available();
                if (size > 0) {
                    byte[] bytes = new byte[size];
                    int read = stream.readNBytes(bytes, 0, size);
                    if (read == size && stream.read() == -1) return bytes;
                    byte[] rest = stream.readAllBytes();
                    byte[] all = java.util.Arrays.copyOf(bytes, read + rest.length);
                    System.arraycopy(rest, 0, all, read, rest.length);
                    return all;
                }
                return stream.readAllBytes();
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to read response wire bytes", e);
            }
        }
    }

    /**
     * Returns the cached {@link DynamicMessageMarshaller} for a proto descriptor,
     * creating it on first access.
     */
    public DynamicMessageMarshaller marshallerFor(Descriptor descriptor) {
        return marshallerCache.computeIfAbsent(descriptor, DynamicMessageMarshaller::forDescriptor);
    }

    /** Exposed for testing: number of contexts currently tracked. */
    int contextCacheSize() {
        contextCache.cleanUp();
        return (int) contextCache.estimatedSize();
    }

    /** Exposed for testing: number of marshallers currently cached. */
    int marshallerCacheSize() {
        return marshallerCache.size();
    }
}
