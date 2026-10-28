package dev.appiaworks.shiguchi.cursus.cli;

/** A failure with the exit code the process should end with. */
public final class CliException extends RuntimeException {

    /** Exit codes of the command line (see docs/push-cli.md). */
    public static final int TRANSPORT = 1;

    public static final int USAGE = 2;
    public static final int NOT_SUPPORTED = 3;
    public static final int AUTH = 4;
    public static final int INTERRUPTED = 130;

    private final int exitCode;
    private final boolean retryable;
    private final boolean unauthorized;

    public CliException(int exitCode, String message) {
        this(exitCode, message, false, false);
    }

    CliException(int exitCode, String message, boolean retryable, boolean unauthorized) {
        super(message);
        this.exitCode = exitCode;
        this.retryable = retryable;
        this.unauthorized = unauthorized;
    }

    public int exitCode() {
        return exitCode;
    }

    /** True for a failure a reconnect may cure: a 5xx, a refused connection. */
    boolean retryable() {
        return retryable;
    }

    /** True when the server said the token is missing or expired (401 / UNAUTHENTICATED). */
    boolean unauthorized() {
        return unauthorized;
    }

    /** Maps an HTTP status to the exit code and flags the CLI promises. */
    static CliException forStatus(int status, String detail) {
        String message = "HTTP " + status + (detail == null || detail.isBlank() ? "" : ": " + detail.strip());
        if (status == 401) return new CliException(AUTH, message, false, true);
        if (status == 403) return new CliException(AUTH, message, false, false);
        if (status == 404 || status == 501) return new CliException(NOT_SUPPORTED, message, false, false);
        boolean retry = status >= 500 || status == 429 || status == 408;
        return new CliException(TRANSPORT, message, retry, false);
    }
}
