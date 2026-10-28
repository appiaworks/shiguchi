package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.HashSet;
import java.util.Set;

/**
 * Builds one {@link AbstractDynamicSerializer} per message type through the
 * {@link SerializerCodegen} this JVM offers.
 *
 * <p>Ask {@link #available()} before touching {@link #compile}: without a generator (a JVM below
 * Java 25, or {@code protobuf-java-extras-classfile} missing) nothing can be built, and the
 * reflective converter serves every message.
 *
 * <p>Which types are scalars, and whether input is read strictly, are decided by the dialect in
 * force when {@code compile} runs and baked into the generated class, so a serializer belongs to
 * the dialect it was compiled under.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public final class DynamicSerializerCompiler {

    private DynamicSerializerCompiler() {}

    /** Whether serializers can be built on this JVM, so {@link #compile} can work. */
    public static boolean available() {
        return Codegen.available();
    }

    /**
     * Builds the serializer for one message type. Nested message fields are served by the
     * reflective converter until {@link AbstractDynamicSerializer#setNested} wires their own
     * serializers in.
     *
     * @throws UnsupportedOperationException when the message reaches {@code google.protobuf.Any},
     *     whose JSON needs a type registry
     * @throws IllegalStateException when no generator is available ({@link #available()})
     */
    public static AbstractDynamicSerializer compile(Descriptor descriptor) throws Exception {
        if (containsAny(descriptor)) {
            throw new UnsupportedOperationException(
                    "message references google.protobuf.Any — requires TypeRegistry (Tier 3)");
        }
        return Codegen.provider().dynamicSerializer(descriptor, ProtoJsonMapping.strictInput());
    }

    /** True when the message reaches {@code google.protobuf.Any} at any depth. */
    public static boolean containsAny(Descriptor d) {
        return containsAny(d, new HashSet<>());
    }

    private static boolean containsAny(Descriptor d, Set<Descriptor> visited) {
        if (!visited.add(d)) return false;
        for (FieldDescriptor fd : d.getFields()) {
            if (fd.getType() != FieldDescriptor.Type.MESSAGE) continue;
            Descriptor md = fd.getMessageType();
            if ("google.protobuf.Any".equals(md.getFullName())) return true;
            if (containsAny(md, visited)) return true;
        }
        return false;
    }
}
