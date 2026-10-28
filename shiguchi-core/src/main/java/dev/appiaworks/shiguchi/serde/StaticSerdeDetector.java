package dev.appiaworks.shiguchi.serde;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.JsonWritable;
import dev.appiaworks.protobuf.extras.serde.Codegen;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.protobuf.extras.serde.TypedMessageWriter;
import dev.appiaworks.protobuf.extras.serde.TypedWriters;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tier 1 serde: classpath-first detection of compiled message classes, each served by a
 * {@link TypedMessageWriter} compiled for it.
 *
 * <p>For every Descriptor in a ServiceEndpointContext, attempts Class.forName() to find the
 * compiled Java proto class on the classpath. If found, and its schema is the service's, a
 * writer is compiled that reads the message through its generated getters — no
 * DynamicMessage, no reflection — the same way the Tier 2 serializers read a DynamicMessage.
 *
 * <p>The dispatch path uses {@link #defaultInstanceFor} to switch the gRPC response marshaller
 * to the generated parser ({@code ProtoUtils.marshaller}) and {@link #writerFor} to write JSON
 * from the resulting typed message — the full Tier 1 fast path.
 *
 * <p>Only applicable to services whose API JARs are on the gateway classpath (e.g. compiled
 * proto stubs) running on Java 25 or later, where the writers can be generated, or built with
 * {@code protoc-gen-java-extras}, whose classes carry their writer on any Java. Elsewhere,
 * and for pure dynamic-only services (no compiled stubs), all types fall through to Tier 0,
 * Tier 2 (generated) or Tier 3 (JsonFormat). A type reaching {@code google.protobuf.Any} is not
 * served here either: its JSON needs a type registry.
 *
 * <p>The writers bake in the {@link ProtoJsonMapping} installed when they are compiled, so a
 * response reaching a {@code google.type} message the mapping writes as text is served here
 * like any other.
 */
@Component
@org.springframework.context.annotation.DependsOn(ProtoJsonMappingConfigurer.BEAN_NAME)
public class StaticSerdeDetector {

    private static final Logger log = LoggerFactory.getLogger(StaticSerdeDetector.class);

    /** The compiled default instance of every served type, across every scanned context. */
    private final Map<Descriptor, Message> defaultInstances = new ConcurrentHashMap<>();

    /** The writer of every served type. */
    private final Map<Descriptor, TypedMessageWriter> writers = new ConcurrentHashMap<>();

    private final Map<Descriptor, Boolean> probed = new ConcurrentHashMap<>();

    /** Said once per JVM, not once per type. */
    private static volatile boolean unavailableLogged;

    /** Writers by generated class, shared across types so a nested class is compiled once. Guarded by scan(). */
    private final Map<Class<? extends Message>, TypedMessageWriter> writersByClass = new HashMap<>();

    public synchronized void scan(ServiceEndpointContext ctx) {
        for (var fd : ctx.getFileDescriptors().values()) {
            for (Descriptor descriptor : fd.getMessageTypes()) {
                tryRegister(descriptor);
            }
        }
    }

    private void tryRegister(Descriptor descriptor) {
        if (probed.putIfAbsent(descriptor, true) != null) return;

        String javaClass = javaClassNameFor(descriptor);
        Message defaultInstance;
        try {
            Class<?> clazz = Class.forName(javaClass);
            defaultInstance = (Message) clazz.getMethod("getDefaultInstance").invoke(null);
            // The compiled class must describe the same schema as the (possibly
            // reflection-built) descriptor, otherwise the generated parser would mis-read
            // the wire bytes.
            if (!defaultInstance.getDescriptorForType().toProto().equals(descriptor.toProto())) {
                log.debug("Tier1 serde skipped {} (classpath schema differs from service schema)", javaClass);
                return;
            }
        } catch (ClassNotFoundException ignored) {
            return; // Expected for dynamic-only services
        } catch (Exception e) {
            log.debug("Tier1 serde skipped {} ({}): {}", descriptor.getFullName(), javaClass, e.getMessage());
            return;
        }

        if (!TypedWriters.available() && !(defaultInstance instanceof JsonWritable)) {
            if (!unavailableLogged) {
                unavailableLogged = true;
                log.info("Tier 1 serde off: {}; compiled stubs are served by Tier 0", Codegen.unavailableReason());
            }
            return;
        }
        try {
            TypedMessageWriter writer = TypedWriters.compile(defaultInstance, writersByClass);
            defaultInstances.put(descriptor, defaultInstance);
            writers.put(descriptor, writer);
            log.debug("Tier1 serde registered: {} -> {}", descriptor.getFullName(), javaClass);
        } catch (UnsupportedOperationException e) {
            log.debug("Tier1 serde skipped {} (reaches google.protobuf.Any)", javaClass);
        } catch (Exception | LinkageError e) {
            log.debug("Tier1 serde skipped {} (writer did not compile): {}", javaClass, e.getMessage());
        }
    }

    public boolean isRegistered(Descriptor descriptor) {
        return writers.containsKey(descriptor);
    }

    /**
     * The compiled default instance for a descriptor, or null when no compiled class is on
     * the classpath (or no writer could be compiled for it).
     */
    public Message defaultInstanceFor(Descriptor descriptor) {
        return defaultInstances.get(descriptor);
    }

    /** The writer for a descriptor {@link #defaultInstanceFor} serves, or null. */
    public TypedMessageWriter writerFor(Descriptor descriptor) {
        return writers.get(descriptor);
    }

    /**
     * Derives the fully-qualified Java class name from the proto Descriptor.
     * Mirrors the protoc Java name generation rules:
     *   java_package + "." + java_outer_classname (if multiple_files=false)
     *   java_package + "." + messageName (if java_multiple_files=true)
     */
    private static String javaClassNameFor(Descriptor descriptor) {
        var fileOpts = descriptor.getFile().getOptions();
        String pkg = fileOpts.getJavaPackage();
        if (!fileOpts.getJavaMultipleFiles()) {
            String outer = fileOpts.getJavaOuterClassname();
            if (outer.isEmpty()) {
                // Default outer class = CamelCase(proto file name)
                outer = toCamelCase(stripProtoExtension(descriptor.getFile().getName()));
            }
            return pkg + "." + outer + "$" + descriptor.getName();
        }
        return pkg + "." + descriptor.getName();
    }

    private static String stripProtoExtension(String filename) {
        int slash = filename.lastIndexOf('/');
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        return base.endsWith(".proto") ? base.substring(0, base.length() - 6) : base;
    }

    private static String toCamelCase(String name) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : name.toCharArray()) {
            if (c == '_' || c == '-') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
