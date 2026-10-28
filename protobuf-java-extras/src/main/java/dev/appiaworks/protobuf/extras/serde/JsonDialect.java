package dev.appiaworks.protobuf.extras.serde;

import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One complete JSON dialect: a {@link ProtoJsonMapping} and the scalar codecs that go with it.
 *
 * <p>The serde's entry points are static, so a dialect reaches them in one of two ways. The
 * gateway installs one for the whole process ({@link ProtoJsonMapping#install}). A
 * {@code ProtoJson} instance instead runs each call inside {@link #call}, which makes its own
 * dialect the one in force on the calling thread for the length of that call. The second wins
 * over the first, which is what lets instances with different dialects share a JVM, and a
 * library use one without disturbing its host.
 */
@ApiStatus(ApiStatus.Status.INTERNAL)
public final class JsonDialect {

    /** A body run with a dialect in force. */
    @FunctionalInterface
    public interface Body<T, E extends Exception> {
        T run() throws E;
    }

    private static final ThreadLocal<JsonDialect> SCOPED = new ThreadLocal<>();

    /**
     * Whether any call has ever been scoped. Until one is, the process-wide lookups skip the
     * thread-local read: a gateway that never builds a {@code ProtoJson} pays one volatile read.
     */
    private static volatile boolean scopesUsed;

    private final ProtoJsonMapping mapping;
    private final Map<String, ProtoScalarCodec> codecs;
    private final boolean strictInput;

    private JsonDialect(ProtoJsonMapping mapping, Map<String, ProtoScalarCodec> codecs, boolean strictInput) {
        this.mapping = mapping;
        this.codecs = codecs;
        this.strictInput = strictInput;
    }

    /**
     * A dialect of the mapping and the application's own codecs. A codec for a {@code google.type}
     * message the mapping also switches on takes its place.
     *
     * @throws IllegalArgumentException when two codecs name the same message, one names a
     *     {@code google.protobuf} well-known type, whose JSON form proto3 fixes, or two declare one
     *     GraphQL scalar name with different schemas
     */
    public static JsonDialect of(ProtoJsonMapping mapping, Collection<? extends ProtoScalarCodec> scalarCodecs) {
        return of(mapping, scalarCodecs, false);
    }

    /**
     * {@link #of(ProtoJsonMapping, Collection)}, reading input strictly or not: see
     * {@link #strictInput()}.
     */
    public static JsonDialect of(
            ProtoJsonMapping mapping, Collection<? extends ProtoScalarCodec> scalarCodecs, boolean strictInput) {
        ProtoJsonMapping m = mapping == null ? ProtoJsonMapping.DEFAULTS : mapping;
        Map<String, ProtoScalarCodec> table = new HashMap<>(BuiltInScalarCodecs.enabledBy(m));
        Set<String> seen = new HashSet<>();
        Map<String, ProtoScalarCodec> graphQlNames = new HashMap<>();
        for (ProtoScalarCodec codec : scalarCodecs) {
            String name = codec.fullName();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ProtoScalarCodec " + codec + " names no message type");
            }
            if (name.startsWith("google.protobuf.")) {
                throw new IllegalArgumentException("ProtoScalarCodec for " + name
                        + ": the JSON form of a google.protobuf type is fixed by proto3");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("More than one ProtoScalarCodec for " + name);
            }
            String graphQlName = codec.schema().graphQlName();
            if (graphQlName != null) {
                ProtoScalarCodec other = graphQlNames.putIfAbsent(graphQlName, codec);
                if (other != null && !other.schema().equals(codec.schema())) {
                    throw new IllegalArgumentException(
                            "GraphQL scalar " + graphQlName + " is declared by the codecs for " + other.fullName()
                                    + " and " + name + " with different schemas; one name is one scalar");
                }
            }
            table.put(name, codec);
        }
        return new JsonDialect(m, Map.copyOf(table), strictInput);
    }

    public ProtoJsonMapping mapping() {
        return mapping;
    }

    /**
     * Whether a value that does not fit its field is refused ({@link StrictScalars}) rather than
     * coerced: {@code "abc"} or {@code 1.5} for an int32 field. Compiled parsers bake this in when
     * they are built.
     */
    public boolean strictInput() {
        return strictInput;
    }

    /** Full name → the codec that writes the message as one JSON value. */
    Map<String, ProtoScalarCodec> codecs() {
        return codecs;
    }

    /**
     * Runs the body with this dialect in force on the calling thread, restoring whatever was in
     * force before — so a codec may itself use another {@code ProtoJson}.
     */
    public <T, E extends Exception> T call(Body<T, E> body) throws E {
        scopesUsed = true;
        JsonDialect previous = SCOPED.get();
        SCOPED.set(this);
        try {
            return body.run();
        } finally {
            if (previous == null) {
                SCOPED.remove();
            } else {
                SCOPED.set(previous);
            }
        }
    }

    /** The dialect a {@link #call} put in force on this thread, or null. */
    static JsonDialect scoped() {
        return scopesUsed ? SCOPED.get() : null;
    }
}
