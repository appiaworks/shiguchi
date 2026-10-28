package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.protobuf.extras.ProtoJsonException;
import java.util.Iterator;
import java.util.ServiceLoader;

/** Builds the exception for a value that cannot become its proto type, in the host's exception type. */
final class MalformedValues {

    private MalformedValues() {}

    /** The host's translator, or null: looked up once, the first time a value is rejected. */
    private static final class Holder {
        static final MalformedValueTranslator TRANSLATOR = load();

        private static MalformedValueTranslator load() {
            Iterator<MalformedValueTranslator> found = ServiceLoader.load(
                            MalformedValueTranslator.class, MalformedValueTranslator.class.getClassLoader())
                    .iterator();
            return found.hasNext() ? found.next() : null;
        }
    }

    static RuntimeException malformed(Descriptor d, Object value, String reason, Throwable cause) {
        return malformed(d.getFullName(), value, reason, cause);
    }

    /** {@link #malformed(Descriptor, Object, String, Throwable)} for a type named in words, such as {@code int32}. */
    static RuntimeException malformed(String typeName, Object value, String reason, Throwable cause) {
        ProtoJsonException e = new ProtoJsonException(typeName, value, reason, cause);
        // A ProtoJson call reports in its own type whatever the host is; the translator is for the
        // host's own, process-wide use of the serde.
        if (JsonDialect.scoped() != null) return e;
        MalformedValueTranslator translator = Holder.TRANSLATOR;
        return translator == null ? e : translator.translate(e);
    }

    static boolean isTranslated(RuntimeException e) {
        if (JsonDialect.scoped() != null) return false;
        MalformedValueTranslator translator = Holder.TRANSLATOR;
        return translator != null && translator.isTranslated(e);
    }
}
