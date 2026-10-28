package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.api.ApiStatus;

/**
 * Generates the classes behind the compiled serde paths. Found through {@code ServiceLoader} by
 * {@link Codegen}; the one implementation lives in {@code protobuf-java-extras-classfile}, which is
 * compiled for Java 25 and so loads only there.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public interface SerializerCodegen {

    /**
     * A writer for the generated message class the plan describes, reading the message through
     * the accessors the plan resolved.
     *
     * @throws Exception when the class cannot be generated or defined
     */
    TypedMessageWriter typedWriter(TypedWriterPlan plan) throws Exception;

    /**
     * A Tier 2 serializer for the message type: {@code serialize}, {@code deserialize},
     * {@code toMap}, the projected {@code toMap} and {@code fromMap} over a {@code DynamicMessage},
     * with the dialect in force baked in.
     *
     * @param strict whether {@code deserialize} and {@code fromMap} refuse a value that does not
     *     fit its field ({@link StrictScalars}) instead of coercing it
     * @throws Exception when the class cannot be generated or defined
     */
    AbstractDynamicSerializer dynamicSerializer(Descriptor descriptor, boolean strict) throws Exception;
}
