package dev.appiaworks.shiguchi.reflection;

import com.google.api.AnnotationsProto;
import com.google.protobuf.ExtensionRegistry;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.proto.api.MergeProto;
import dev.appiaworks.shiguchi.proto.api.MethodProto;
import dev.appiaworks.shiguchi.proto.api.TopicProto;

/**
 * Shared {@link ExtensionRegistry} for parsing proto descriptors received over the wire
 * (gRPC server reflection) or read from {@code .pb} files.
 *
 * <p>Without a registry, {@code google.api.http}, {@code (shiguchi.api.*)} method and field options
 * arrive as unknown fields, {@code getExtension()} silently returns the default instance,
 * and every REST/GraphQL binding derived from annotations is dropped.
 */
public final class GatewayExtensionRegistry {

    private static final ExtensionRegistry REGISTRY = create();

    private static ExtensionRegistry create() {
        ExtensionRegistry registry = ExtensionRegistry.newInstance();
        AnnotationsProto.registerAllExtensions(registry);
        GraphQlProto.registerAllExtensions(registry);
        MethodProto.registerAllExtensions(registry);
        MergeProto.registerAllExtensions(registry);
        TopicProto.registerAllExtensions(registry);
        return registry;
    }

    /** The registry with all gateway-relevant descriptor extensions registered. */
    public static ExtensionRegistry get() {
        return REGISTRY;
    }

    private GatewayExtensionRegistry() {}
}
