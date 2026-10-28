package dev.appiaworks.shiguchi.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request access logging on the logger {@code dev.appiaworks.shiguchi.access}: one line per
 * request with remote IP ({@code X-Forwarded-For}-aware), HTTP method, path, matched
 * gRPC route, HTTP status, gRPC status, duration, principal, and user agent.
 * Route log output to files/collectors via your logging backend's appender config.
 */
@Data
@NoArgsConstructor
public class AccessLogConfig {
    private boolean enabled = false;
    /** {@code COMMON} = single text line; {@code JSON} = one JSON object per line (ELK/Splunk-ready). */
    private Format format = Format.COMMON;

    public enum Format {
        COMMON,
        JSON
    }
}
