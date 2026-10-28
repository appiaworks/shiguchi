package dev.appiaworks.protobuf.extras.serde;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The {@link SerializerCodegen} this JVM offers, or none.
 *
 * <p>The provider is compiled for Java 25: on an older JVM {@code ServiceLoader} fails to load it
 * with a {@link ServiceConfigurationError} wrapping an {@code UnsupportedClassVersionError}, and
 * that is read as "no generator here", not as a failure. The serde then runs on its wire
 * transcoder and reflective paths, same output, more work per message.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public final class Codegen {

    private static final SerializerCodegen PROVIDER = load();

    private Codegen() {}

    /** Whether a generator is on this JVM: the classfile module is on the classpath and it is Java 25+. */
    public static boolean available() {
        return PROVIDER != null;
    }

    /** The reason {@link #available()} is false, for a log line; null when it is true. */
    public static String unavailableReason() {
        if (PROVIDER != null) return null;
        int feature = Runtime.version().feature();
        return feature < 25
                ? "the compiled serializers need Java 25 or later, this is Java " + feature
                : "protobuf-java-extras-classfile is not on the classpath";
    }

    /**
     * @throws IllegalStateException when no generator is available
     */
    public static SerializerCodegen provider() {
        if (PROVIDER == null) throw new IllegalStateException("No serializer code generator: " + unavailableReason());
        return PROVIDER;
    }

    private static SerializerCodegen load() {
        SerializerCodegen found = load(Codegen.class.getClassLoader());
        if (found == null) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null && context != Codegen.class.getClassLoader()) found = load(context);
        }
        return found;
    }

    private static SerializerCodegen load(ClassLoader loader) {
        try {
            Iterator<SerializerCodegen> providers =
                    ServiceLoader.load(SerializerCodegen.class, loader).iterator();
            while (providers.hasNext()) {
                try {
                    return providers.next();
                } catch (ServiceConfigurationError | LinkageError e) {
                    // A provider this JVM cannot load: compiled for a newer Java, or broken.
                }
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            // No usable provider.
        }
        return null;
    }
}
