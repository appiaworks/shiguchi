package dev.appiaworks.shiguchi.cursus.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** The bearer token: a fixed value, or the stdout of a command that is run again on a 401. */
public final class TokenSource {

    private final String command;
    private volatile String token;

    public TokenSource(String token, String command) {
        this.token = token == null || token.isBlank() ? null : token.strip();
        this.command = command == null || command.isBlank() ? null : command;
    }

    public static TokenSource none() {
        return new TokenSource(null, null);
    }

    /** The current token, running the command the first time when no fixed token is set; null when none. */
    public synchronized String current() {
        if (token == null && command != null) token = run();
        return token;
    }

    /** True when {@link #refresh()} can produce a new token. */
    public boolean canRefresh() {
        return command != null;
    }

    /** Runs the token command again; returns the new token. */
    public synchronized String refresh() {
        if (command == null) return token;
        token = run();
        return token;
    }

    private String run() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder builder =
                windows ? new ProcessBuilder("cmd", "/c", command) : new ProcessBuilder("sh", "-c", command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = builder.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CliException(CliException.AUTH, "--token-cmd timed out after 30 s");
            }
            if (process.exitValue() != 0) {
                throw new CliException(CliException.AUTH, "--token-cmd exited with " + process.exitValue());
            }
            String value = out.strip();
            if (value.isEmpty()) throw new CliException(CliException.AUTH, "--token-cmd printed no token");
            return value;
        } catch (IOException e) {
            throw new CliException(CliException.AUTH, "--token-cmd failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(CliException.INTERRUPTED, "interrupted");
        }
    }
}
