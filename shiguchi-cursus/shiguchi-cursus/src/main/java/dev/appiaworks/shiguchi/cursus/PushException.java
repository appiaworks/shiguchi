package dev.appiaworks.shiguchi.cursus;

import org.springframework.http.HttpStatus;

/** A request the push surface refuses, with the HTTP status and a short machine-readable code. */
public final class PushException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PushException(HttpStatus status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static PushException badRequest(String message) {
        return new PushException(HttpStatus.BAD_REQUEST, "bad_request", message);
    }

    public static PushException unknownNamespace(Topic topic) {
        return new PushException(
                HttpStatus.NOT_FOUND,
                "unknown_namespace",
                "namespace '" + topic.namespace() + "' is not declared under shiguchi.cursus.namespaces");
    }

    public static PushException unauthenticated(String message) {
        return new PushException(HttpStatus.UNAUTHORIZED, "unauthenticated", message);
    }

    public static PushException forbidden(String message) {
        return new PushException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static PushException notFound(String message) {
        return new PushException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    public static PushException conflict(String message) {
        return new PushException(HttpStatus.CONFLICT, "conflict", message);
    }

    public static PushException tooLarge(int size, int max) {
        return new PushException(
                HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "payload of " + size + " bytes exceeds " + max);
    }
}
