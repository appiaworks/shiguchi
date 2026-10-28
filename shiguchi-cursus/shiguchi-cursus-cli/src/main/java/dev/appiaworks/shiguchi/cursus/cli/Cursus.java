package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintWriter;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * {@code cursus}: subscribe, publish, list and search a gateway's push surface from a terminal.
 * NDJSON on stdout, diagnostics on stderr; exit codes: 0 ok, 1 transport or HTTP error, 2 usage,
 * 3 not supported by this gateway, 4 authentication, 130 interrupted.
 */
@Command(
        name = "cursus",
        mixinStandardHelpOptions = true,
        versionProvider = Cursus.Version.class,
        description = "Command line for Cursus, the Shiguchi push surface.",
        subcommands = {
            SubCommand.class,
            PubCommand.class,
            LsCommand.class,
            FindCommand.class,
            RepubCommand.class,
            RmCommand.class,
            DlqCommand.class,
            SeekCommand.class,
            SendCommand.class,
            BenchCommand.class
        },
        footer = {
            "",
            "Exit codes: 0 ok, 1 transport/HTTP error, 2 usage, 3 not supported (404/501), 4 auth (401/403), 130 interrupted."
        })
public final class Cursus {

    @Option(
            names = "--url",
            scope = ScopeType.INHERIT,
            description = "Gateway base URL (default http://localhost:8080, or CURSUS_URL).")
    String url;

    @Option(
            names = "--actuator-url",
            scope = ScopeType.INHERIT,
            description = "Actuator base URL (default <url>/actuator).")
    String actuatorUrl;

    @Option(names = "--push-path", scope = ScopeType.INHERIT, description = "Path of the push surface (default /push).")
    String pushPath;

    @Option(
            names = "--grpc-target",
            scope = ScopeType.INHERIT,
            description = "host:port of the gateway's gRPC port for --grpc (default <url host>:9090).")
    String grpcTarget;

    @Option(
            names = "--profile",
            scope = ScopeType.INHERIT,
            description = "Profile of ~/.cursus/config.yml (or CURSUS_PROFILE).")
    String profile;

    @Option(names = "--token", scope = ScopeType.INHERIT, description = "Bearer token (or CURSUS_TOKEN).")
    String token;

    @Option(
            names = "--token-cmd",
            scope = ScopeType.INHERIT,
            description = "Command whose stdout is the token; run again after a 401.")
    String tokenCmd;

    @Option(names = "--basic", scope = ScopeType.INHERIT, description = "user:pass for the actuator endpoint.")
    String basic;

    @Option(
            names = "--insecure",
            scope = ScopeType.INHERIT,
            description = "Do not verify the server's TLS certificate. Never the default.")
    Boolean insecure;

    @Option(
            names = "--pretty",
            scope = ScopeType.INHERIT,
            description = "Human-readable output (tables, one line per event) instead of NDJSON.")
    boolean pretty;

    @Option(
            names = {"-q", "--quiet"},
            scope = ScopeType.INHERIT,
            description = "No diagnostics on stderr.")
    boolean quiet;

    private PrintWriter out;
    private PrintWriter err;
    private Map<String, String> env = System.getenv();
    private Lifecycle lifecycle = new Lifecycle();
    private Settings settings;
    private Http http;
    private Out printer;

    /** Runs the command line in-process; stdout and stderr go to the given writers. Returns the exit code. */
    public static int execute(PrintWriter out, PrintWriter err, String... args) {
        return execute(out, err, System.getenv(), new Lifecycle(), args);
    }

    /** As {@link #execute(PrintWriter, PrintWriter, String...)} with an explicit environment and lifecycle. */
    public static int execute(
            PrintWriter out, PrintWriter err, Map<String, String> env, Lifecycle lifecycle, String... args) {
        Cursus root = new Cursus();
        root.out = out;
        root.err = err;
        root.env = env;
        root.lifecycle = lifecycle;
        CommandLine cmd = new CommandLine(root);
        cmd.setOut(out);
        cmd.setErr(err);
        cmd.setCaseInsensitiveEnumValuesAllowed(true);
        LsCommand.registerAliases(cmd);
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            if (ex instanceof CliException c) {
                err.println("cursus: " + c.getMessage());
                err.flush();
                return c.exitCode();
            }
            err.println("cursus: " + ex);
            err.flush();
            return CliException.TRANSPORT;
        });
        int code = cmd.execute(args);
        out.flush();
        err.flush();
        return lifecycle.wasInterrupted() ? CliException.INTERRUPTED : code;
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(System.out), false);
        PrintWriter err = new PrintWriter(new java.io.OutputStreamWriter(System.err), true);
        Lifecycle lifecycle = new Lifecycle();
        Thread main = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            lifecycle.stop(true);
            try {
                main.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            out.flush();
        }));
        int code = execute(out, err, System.getenv(), lifecycle, args);
        out.flush();
        System.exit(code);
    }

    Lifecycle lifecycle() {
        return lifecycle;
    }

    Out out() {
        if (printer == null) printer = new Out(out, err, pretty, quiet);
        return printer;
    }

    Settings settings() {
        if (settings == null) {
            settings = Settings.resolve(
                    new Settings.Flags(
                            profile, url, actuatorUrl, pushPath, grpcTarget, token, tokenCmd, basic, insecure),
                    env);
            if (settings.insecure() && !quiet) {
                err.println("cursus: warning: --insecure: the server's TLS certificate is not verified");
                err.flush();
            }
        }
        return settings;
    }

    Http http() {
        if (http == null) http = new Http(settings());
        return http;
    }

    /** Prints each element of an array under {@code field} as one NDJSON line (or a table). */
    void printArray(JsonNode body, String field, String... columns) {
        JsonNode items = body.path(field);
        out().rows(items, columns);
    }

    /** The version printed by {@code --version}. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = Cursus.class.getPackage().getImplementationVersion();
            return new String[] {"cursus " + (v == null ? "dev" : v)};
        }
    }
}
