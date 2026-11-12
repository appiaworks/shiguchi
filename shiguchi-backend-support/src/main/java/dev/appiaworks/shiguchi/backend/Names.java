package dev.appiaworks.shiguchi.backend;

import io.grpc.Status;
import java.util.Locale;

/** Name matching and error construction shared by the helpers in this package. */
final class Names {

    private Names() {}

    /**
     * The form two spellings of one field share: {@code published_year} and {@code publishedYear}
     * both become {@code publishedyear}. REST callers send camelCase, the mask the gateway builds
     * is snake_case, and a backend should not have to care which one arrived.
     */
    static String normalize(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    /** A client-supplied string made safe to echo into an error message. */
    static String echo(String value) {
        String clean = value.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > 64 ? clean.substring(0, 64) + "..." : clean;
    }

    static RuntimeException invalid(String message) {
        return Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException();
    }
}
