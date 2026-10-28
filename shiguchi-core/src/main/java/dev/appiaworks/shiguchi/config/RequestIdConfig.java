package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The id that identifies one request in the logs ({@code shiguchi.request-id.*}).
 *
 * <p>Every request gets one: the caller's {@code X-Request-Id} when they sent it, so a trace
 * that began at an ingress keeps its identity, else a fresh UUID. It is put in the Reactor
 * context and in SLF4J's MDC under {@code requestId}, so a logging pattern can print it
 * without every log statement having to carry it, and echoed back to the caller so they can
 * quote it. It is also already forwarded to backends as {@code x-request-id} by
 * {@code client.forward-tracing-headers}.
 */
@Data
@NoArgsConstructor
public class RequestIdConfig {
    /** Assign an id, put it in the logging context, and echo it back. */
    private boolean enabled = true;

    /** Header the id is read from and echoed in. */
    private String header = "X-Request-Id";

    /**
     * Reuse the caller's id when they send one. Turn this off on an internet-facing gateway:
     * the id reaches the logs, so a caller who chooses it can make unrelated requests look
     * like one, or write awkward text into a line someone later greps. A caller-supplied id
     * is always trimmed to 128 printable characters first.
     */
    private boolean trustInbound = true;

    /** Send the id back on the response. */
    private boolean includeInResponse = true;
}
