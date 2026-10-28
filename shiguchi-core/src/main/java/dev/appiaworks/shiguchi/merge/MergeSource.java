package dev.appiaworks.shiguchi.merge;

import java.util.Objects;

/**
 * One participant in a {@link MergeSpec}: the backend RPC whose response stream is merged with the
 * others.
 *
 * @param endpoint the gateway service registration this source lives in (the {@code name} under
 *     {@code shiguchi.services}), or empty to match on service and method alone. Required when
 *     several registered backends expose the <em>same</em> proto service — the sharded case, where
 *     every shard serves an identical {@code .proto} and only the address differs. Without it those
 *     sources are indistinguishable and the merge would read one backend N times.
 * @param fullServiceName proto service name, e.g. {@code "bookstore.v1.BookService"}
 * @param methodName RPC name, e.g. {@code "getRefByIds"}
 * @param presorted whether this backend returns its stream <strong>already ordered by the merge
 *     key</strong>. This is the single most consequential flag in the merge: a k-way merge is a
 *     merge <em>of sorted sequences</em>, so an unsorted source declared {@code true} produces a
 *     silently wrong global order — the heap only ever compares the currently visible heads, and a
 *     smaller record buried later in the stream is never reconsidered. The gateway cannot verify
 *     the claim at runtime (it can pass {@code sort_fields} down, but not check that the backend
 *     honoured it), so this defaults to {@code false} and must be set deliberately by someone who
 *     has confirmed the backend orders its output.
 */
public record MergeSource(String endpoint, String fullServiceName, String methodName, boolean presorted) {

    public MergeSource {
        Objects.requireNonNull(fullServiceName, "fullServiceName");
        Objects.requireNonNull(methodName, "methodName");
        endpoint = endpoint != null ? endpoint : "";
        if (fullServiceName.isBlank()) {
            throw new IllegalArgumentException("fullServiceName must not be blank");
        }
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
    }

    /** A source that must be materialised and sorted gateway-side before it can be merged. */
    public static MergeSource unsorted(String fullServiceName, String methodName) {
        return new MergeSource("", fullServiceName, methodName, false);
    }

    /** A source the backend guarantees is already ordered by the merge key. */
    public static MergeSource presorted(String fullServiceName, String methodName) {
        return new MergeSource("", fullServiceName, methodName, true);
    }

    /** Label used in logs and error messages; includes the endpoint when one is set. */
    public String label() {
        return endpoint.isEmpty()
                ? fullServiceName + "/" + methodName
                : endpoint + ":" + fullServiceName + "/" + methodName;
    }
}
