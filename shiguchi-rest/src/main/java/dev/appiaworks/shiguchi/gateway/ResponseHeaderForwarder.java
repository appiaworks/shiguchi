package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * Copies a backend's response metadata onto the HTTP response, limited to the names
 * {@code forward-response-headers} lists ({@code *} for all).
 *
 * <p>Some names never cross, whatever the list says: gRPC's own ({@code grpc-*}) and
 * pseudo-headers, binary {@code -bin} metadata, which has no text form, headers that describe the
 * HTTP/2 framing between gateway and backend rather than the HTTP response ({@code content-type},
 * {@code content-length}, hop-by-hop headers), and any header the gateway already set on the
 * response (pagination, request id, security headers), which the backend must not be able to
 * replace.
 */
final class ResponseHeaderForwarder {

    private static final Set<String> NEVER = Set.of(
            "content-type",
            "content-length",
            "content-encoding",
            "transfer-encoding",
            "connection",
            "keep-alive",
            "proxy-connection",
            "te",
            "trailer",
            "upgrade",
            "host",
            "user-agent");

    private final boolean all;
    private final Set<String> names;

    private ResponseHeaderForwarder(boolean all, Set<String> names) {
        this.all = all;
        this.names = names;
    }

    /** A forwarder for the configured names, or null when there are none. */
    static ResponseHeaderForwarder of(List<String> configured) {
        if (configured == null || configured.isEmpty()) return null;
        Set<String> names = new HashSet<>();
        boolean all = false;
        for (String name : configured) {
            if (name == null || name.isBlank()) continue;
            String n = name.trim().toLowerCase(Locale.ROOT);
            if (n.equals("*")) all = true;
            else names.add(n);
        }
        return all || !names.isEmpty() ? new ResponseHeaderForwarder(all, Set.copyOf(names)) : null;
    }

    /**
     * Copies the captured headers, then (when asked) the trailers, onto {@code out}. Only
     * {@code getFirst} and {@code add} are used on {@code out}: Spring 7's {@code HttpHeaders} is
     * no longer a {@code Map}, and this class is compiled once for both Boot lines.
     */
    void apply(ResponseMetadataCapture capture, HttpHeaders out, boolean withTrailers) {
        // Collected before writing, so a name in both headers and trailers keeps both values while
        // a name the gateway set itself is still recognised as the gateway's.
        Map<String, List<String>> forwarded = new LinkedHashMap<>();
        collect(capture.headers(), out, forwarded);
        if (withTrailers) collect(capture.trailers(), out, forwarded);
        forwarded.forEach((name, values) -> values.forEach(value -> out.add(name, value)));
    }

    private void collect(Metadata metadata, HttpHeaders out, Map<String, List<String>> forwarded) {
        if (metadata == null) return;
        for (String key : metadata.keys()) {
            if (!forwards(key) || out.getFirst(key) != null) continue;
            Iterable<String> values = metadata.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            if (values == null) continue;
            List<String> list = forwarded.computeIfAbsent(key, k -> new ArrayList<>());
            values.forEach(list::add);
        }
    }

    /** Whether a metadata key (gRPC keeps them lowercase) may be copied. */
    boolean forwards(String key) {
        if (key.startsWith(":") || key.startsWith("grpc-") || key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) return false;
        if (NEVER.contains(key)) return false;
        return all || names.contains(key);
    }
}
