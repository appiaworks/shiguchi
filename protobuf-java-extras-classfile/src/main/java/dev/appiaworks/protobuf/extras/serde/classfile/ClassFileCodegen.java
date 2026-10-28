package dev.appiaworks.protobuf.extras.serde.classfile;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.serde.AbstractDynamicSerializer;
import dev.appiaworks.protobuf.extras.serde.SerializerCodegen;
import dev.appiaworks.protobuf.extras.serde.TypedMessageWriter;
import dev.appiaworks.protobuf.extras.serde.TypedWriterPlan;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The {@link SerializerCodegen} of this JVM: classes built with the Class-File API and defined
 * as hidden classes, so they are unloadable with the serde that holds them and never visible by
 * name.
 *
 * <p>A typed writer is defined in the package of the class it reads, through a private lookup on
 * it, so it links against that class in whatever loader holds it; when the lookup is refused (a
 * named module that does not open the package) it is defined here instead, which works whenever
 * this module's loader can see the class. A Tier 2 serializer reads only {@code DynamicMessage}
 * and is defined here.
 */
public final class ClassFileCodegen implements SerializerCodegen {

    private static final MethodHandles.Lookup OWN = MethodHandles.lookup();

    @Override
    public TypedMessageWriter typedWriter(TypedWriterPlan plan) throws Exception {
        MethodHandles.Lookup lookup = lookupFor(plan.type());
        byte[] bytes = new TypedWriterEmitter(plan, lookup).emit();
        return (TypedMessageWriter) instantiate(lookup, bytes, plan.descriptor());
    }

    @Override
    public AbstractDynamicSerializer dynamicSerializer(Descriptor descriptor, boolean strict) throws Exception {
        byte[] bytes = new DynamicSerializerEmitter(descriptor, strict, OWN).emit();
        return (AbstractDynamicSerializer) instantiate(OWN, bytes, descriptor);
    }

    /** Defines the class as a hidden class of the lookup and calls its {@code (Descriptor)} constructor. */
    private static Object instantiate(MethodHandles.Lookup lookup, byte[] bytes, Descriptor descriptor)
            throws Exception {
        Class<?> hidden = lookup.defineHiddenClass(bytes, true).lookupClass();
        MethodHandle constructor = MethodHandles.privateLookupIn(hidden, lookup)
                .findConstructor(hidden, MethodType.methodType(void.class, Descriptor.class));
        try {
            return constructor.invoke(descriptor);
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MethodHandles.Lookup lookupFor(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, OWN);
        } catch (IllegalAccessException e) {
            return OWN;
        }
    }
}
