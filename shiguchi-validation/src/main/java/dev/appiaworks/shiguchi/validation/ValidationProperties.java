package dev.appiaworks.shiguchi.validation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything under {@code shiguchi.validation}: whether protovalidate constraints declared in
 * the protos are checked at the gateway, at an embedded gRPC server, or not at all.
 *
 * <p>Checking at the gateway turns a violation into an HTTP 400 ({@code INVALID_ARGUMENT}) before
 * the call leaves, which saves a round trip but does not replace validation in the backend — the
 * gateway is not the only way in. Violation paths are logged, never field values.
 */
@Data
@ConfigurationProperties(prefix = "shiguchi.validation")
public class ValidationProperties {

    /**
     * Enable proto constraint validation at the gateway layer (Option A).
     * Validates the DynamicMessage after deserialization, before the gRPC call is dispatched.
     * Returns HTTP 400 / gRPC INVALID_ARGUMENT on violation.
     */
    private boolean gatewayEnabled = false;

    /**
     * Enable proto constraint validation at the downstream gRPC server layer (Option B).
     * Registers a ServerInterceptor that validates each incoming request message.
     * Use this in downstream services that depend on shiguchi-validation directly.
     */
    private boolean serverEnabled = false;

    /**
     * When true, a validation failure at the gateway layer logs the full violation detail at WARN.
     * Field values are not logged — only field paths and constraint names.
     */
    private boolean logViolations = true;
}
