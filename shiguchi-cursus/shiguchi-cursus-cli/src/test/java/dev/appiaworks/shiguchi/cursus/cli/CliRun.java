package dev.appiaworks.shiguchi.cursus.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/** Runs {@link Cursus} in-process with captured stdout and stderr. */
final class CliRun {

    record Result(int code, String out, String err) {
        List<String> lines() {
            return out.isBlank() ? List.of() : out.lines().toList();
        }
    }

    private CliRun() {}

    static Result run(String... args) {
        return run(Map.of("CURSUS_CONFIG", "/nonexistent/cursus-config.yml"), new Lifecycle(), args);
    }

    static Result run(Map<String, String> env, Lifecycle lifecycle, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Cursus.execute(new PrintWriter(out), new PrintWriter(err), env, lifecycle, args);
        return new Result(code, out.toString(), err.toString());
    }
}
