package dev.appiaworks.shiguchi.grpc;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The comments a {@code .proto} was written with, read out of a descriptor's
 * {@link SourceCodeInfo}.
 *
 * <p>Documentation already exists in almost every proto — above the service, above the RPC, above
 * each field — and it was being thrown away: the gateway's OpenAPI document and GraphQL schema
 * carried only whatever someone had separately retyped into a {@code (shiguchi.api.graphql)}
 * annotation. Reading it means an API that was documented once is documented on every surface.
 *
 * <p>Only available when the descriptor carries source info, which is the {@code PB_FILE} case:
 * {@code protoc --include_source_info} keeps it, gRPC server reflection does not send it, and
 * compiled Java stubs do not retain it. Callers get an empty map otherwise and fall back to
 * whatever they did before.
 *
 * <p>Paths into a descriptor are the awkward part. {@code SourceCodeInfo} addresses each element
 * by the sequence of field numbers and indexes that reaches it in the {@code FileDescriptorProto}
 * — a service is {@code [6, i]}, its method {@code [6, i, 2, j]} — so this class turns those into
 * names once and hands back a plain map.
 */
public final class ProtoComments {

    /** {@code FileDescriptorProto.service} field number. */
    private static final int SERVICE = 6;
    /** {@code FileDescriptorProto.message_type} field number. */
    private static final int MESSAGE = 4;
    /** {@code ServiceDescriptorProto.method} field number. */
    private static final int METHOD = 2;
    /** {@code DescriptorProto.field} field number. */
    private static final int FIELD = 2;

    private ProtoComments() {}

    /**
     * Comments in one file, keyed by the fully-qualified name of what they document:
     * {@code pkg.Service}, {@code pkg.Service.Method}, {@code pkg.Message},
     * {@code pkg.Message.field}.
     *
     * <p>Empty when the descriptor has no source info, which is the common case — reflection and
     * compiled stubs both drop it.
     */
    public static Map<String, String> of(FileDescriptorProto file) {
        if (file == null || !file.hasSourceCodeInfo()) return Map.of();

        String pkg = file.getPackage().isEmpty() ? "" : file.getPackage() + ".";
        Map<String, String> comments = new HashMap<>();

        for (SourceCodeInfo.Location location : file.getSourceCodeInfo().getLocationList()) {
            String comment = commentOf(location);
            if (comment == null) continue;

            List<Integer> path = location.getPathList();
            // Services and their methods.
            if (path.size() == 2 && path.get(0) == SERVICE) {
                comments.put(pkg + file.getService(path.get(1)).getName(), comment);
            } else if (path.size() == 4 && path.get(0) == SERVICE && path.get(2) == METHOD) {
                var service = file.getService(path.get(1));
                comments.put(
                        pkg + service.getName() + "."
                                + service.getMethod(path.get(3)).getName(),
                        comment);
            } else if (path.size() == 2 && path.get(0) == MESSAGE) {
                comments.put(pkg + file.getMessageType(path.get(1)).getName(), comment);
            } else if (path.size() == 4 && path.get(0) == MESSAGE && path.get(2) == FIELD) {
                var message = file.getMessageType(path.get(1));
                comments.put(
                        pkg + message.getName() + "."
                                + message.getField(path.get(3)).getName(),
                        comment);
            }
            // Nested messages and enums are addressed by longer paths. Left alone deliberately:
            // the surfaces that consume these only name top-level types and their fields, so
            // resolving deeper paths would build a map nothing reads.
        }
        return Map.copyOf(comments);
    }

    /**
     * The comment attached to one location, tidied for use as a description.
     *
     * <p>Leading comments win over trailing ones — a block above a declaration is documentation,
     * a note after it on the same line is usually an aside. protoc keeps the text verbatim
     * including the space after each {@code //}, so each line is stripped and blank-only results
     * are discarded.
     */
    private static String commentOf(SourceCodeInfo.Location location) {
        String raw = !location.getLeadingComments().isBlank()
                ? location.getLeadingComments()
                : location.getTrailingComments();
        if (raw == null || raw.isBlank()) return null;

        String cleaned = raw.lines().map(String::trim).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        return cleaned.isBlank() ? null : cleaned.trim();
    }
}
