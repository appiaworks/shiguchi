package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Compiles a set of Tier 2 serializers and wires each to the serializers of the message types
 * its fields reach — the one procedure behind {@code ProtoJson} and the gateway's
 * {@code DynamicSerializerFactory}, which used to carry a copy each.
 *
 * <p>Wiring happens before anything is returned, so a serializer a caller can see is never still
 * being wired. Single-value types ({@link ProtoWkt#isWkt}) are never compiled: their JSON is one
 * value, written by {@link ProtoWkt}.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public final class SerializerCompilation {

    private SerializerCompilation() {}

    /**
     * Compiles every type in {@code types} that {@code known} has no serializer for, then wires
     * nested references among the new serializers and the known ones.
     *
     * @param types the message types to serve; single-value types are skipped
     * @param known the serializer a type already has, or null — consulted for the types themselves
     *     and for the types their fields reach
     * @param onFailure told of every type that did not compile (a type reaching
     *     {@code google.protobuf.Any}, or no generator on this JVM); such a type is simply absent from
     *     the result, and the reflective converter serves it
     * @return the new serializers by type, wired
     */
    public static Map<Descriptor, AbstractDynamicSerializer> compile(
            Collection<Descriptor> types,
            Function<Descriptor, AbstractDynamicSerializer> known,
            BiConsumer<Descriptor, Throwable> onFailure) {
        Map<Descriptor, AbstractDynamicSerializer> fresh = new LinkedHashMap<>();
        for (Descriptor type : types) {
            if (ProtoWkt.isWkt(type) || fresh.containsKey(type) || known.apply(type) != null) continue;
            try {
                fresh.put(type, DynamicSerializerCompiler.compile(type));
            } catch (Exception | LinkageError e) {
                onFailure.accept(type, e);
            }
        }
        for (Descriptor type : types) {
            AbstractDynamicSerializer serializer = fresh.get(type);
            if (serializer == null) serializer = known.apply(type);
            if (serializer == null) continue;
            for (FieldDescriptor field : type.getFields()) {
                if (field.getType() != FieldDescriptor.Type.MESSAGE || field.isMapField()) continue;
                Descriptor nested = field.getMessageType();
                AbstractDynamicSerializer wired = fresh.get(nested);
                if (wired == null) wired = known.apply(nested);
                if (wired != null) serializer.setNested(nested.getFullName(), wired);
            }
        }
        return fresh;
    }

    /**
     * The type and the message types its fields reach, at any depth, in discovery order.
     * Single-value types and map entries are left out.
     */
    public static List<Descriptor> reachable(Descriptor root) {
        List<Descriptor> out = new ArrayList<>();
        collectReachable(root, out, new HashSet<>());
        return out;
    }

    private static void collectReachable(Descriptor type, List<Descriptor> out, Set<Descriptor> seen) {
        if (!seen.add(type) || ProtoWkt.isWkt(type)) return;
        out.add(type);
        for (FieldDescriptor field : type.getFields()) {
            if (field.getType() == FieldDescriptor.Type.MESSAGE && !field.isMapField()) {
                collectReachable(field.getMessageType(), out, seen);
            }
        }
    }

    /** The message types a file declares, nested declarations included, map entries left out. */
    public static List<Descriptor> declared(FileDescriptor file) {
        List<Descriptor> out = new ArrayList<>();
        for (Descriptor type : file.getMessageTypes()) collectDeclared(type, out);
        return out;
    }

    private static void collectDeclared(Descriptor type, List<Descriptor> out) {
        if (type.getOptions().getMapEntry()) return;
        out.add(type);
        for (Descriptor nested : type.getNestedTypes()) collectDeclared(nested, out);
    }
}
