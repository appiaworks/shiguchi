package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/** {@code cursus ls}: the admin reads of the {@code gateway-push} endpoint. */
@Command(
        name = "ls",
        mixinStandardHelpOptions = true,
        description = "List topics (default), namespaces, connections, sessions or consumer groups.",
        footer = {
            "",
            "Examples:",
            "  cursus ls topics --filter 'orders:*'",
            "  cursus ls conns --principal alice --pretty",
            "Shortcuts: cursus topics | ns | conns | sessions | groups"
        })
final class LsCommand implements Callable<Integer> {

    /** What to list. */
    enum Kind {
        TOPICS,
        NS,
        NAMESPACES,
        CONNS,
        CONNECTIONS,
        SESSIONS,
        GROUPS,
        INFO
    }

    @ParentCommand
    Cursus root;

    @Parameters(
            arity = "0..1",
            paramLabel = "WHAT",
            description = "topics | ns | conns | sessions | groups | info (default topics).")
    Kind what = Kind.TOPICS;

    @Option(
            names = {"-f", "--filter"},
            description = "Name prefix or text filter (the endpoint's q parameter).")
    String filter;

    @Option(names = "--limit", description = "At most this many rows (sessions, groups).")
    Integer limit;

    @Option(names = "--principal", description = "Connections: only this principal.")
    String principal;

    @Option(names = "--topic", description = "Connections: only subscribers of this topic.")
    String topic;

    @Option(names = "--transport", description = "Connections: sse, ws or grpc.")
    String transport;

    LsCommand() {}

    LsCommand(Kind kind) {
        this.what = kind;
    }

    /** Registers {@code topics}, {@code ns}, {@code conns}, {@code sessions}, {@code groups} as direct commands. */
    static void registerAliases(CommandLine root) {
        for (Map.Entry<String, Kind> e : Map.of(
                        "topics", Kind.TOPICS,
                        "ns", Kind.NS,
                        "namespaces", Kind.NS,
                        "conns", Kind.CONNS,
                        "connections", Kind.CONNS,
                        "sessions", Kind.SESSIONS,
                        "groups", Kind.GROUPS)
                .entrySet()) {
            root.addSubcommand(e.getKey(), new CommandLine(new LsCommand(e.getValue())));
        }
    }

    @Override
    public Integer call() {
        Http http = root.http();
        Out out = root.out();
        switch (what) {
            case TOPICS ->
                out.rows(
                        http.getAdmin("/topics" + query(Map.entry("q", nz(filter))))
                                .path("topics"),
                        "/topic",
                        "/subscribers",
                        "/history",
                        "/upstream");
            case NS, NAMESPACES ->
                out.rows(
                        http.getAdmin("/namespaces").path("namespaces"),
                        "/name",
                        "/qos",
                        "/auth",
                        "/activeTopics",
                        "/subscribers");
            case CONNS, CONNECTIONS ->
                out.rows(
                        http.getAdmin("/connections"
                                        + query(
                                                Map.entry("principal", nz(principal)),
                                                Map.entry("topic", nz(topic)),
                                                Map.entry("transport", nz(transport))))
                                .path("connections"),
                        "/id",
                        "/transport",
                        "/principal",
                        "/since",
                        "/topics",
                        "/instance");
            case SESSIONS ->
                out.rows(
                        http.getAdmin("/sessions" + query(Map.entry("q", nz(filter)), Map.entry("limit", nz(limit))))
                                .path("sessions"),
                        "/session",
                        "/topic",
                        "/cursor",
                        "/expiresAt");
            case GROUPS ->
                out.rows(
                        http.getAdmin("/groups" + query(Map.entry("q", nz(filter)), Map.entry("limit", nz(limit))))
                                .path("groups"),
                        "/group",
                        "/topic",
                        "/pending",
                        "/members",
                        "/cursor");
            case INFO -> {
                JsonNode overview = http.getAdmin("");
                out.json(overview);
            }
        }
        return 0;
    }

    @SafeVarargs
    private static String query(Map.Entry<String, String>... params) {
        List<Map.Entry<String, String>> list = new ArrayList<>(List.of(params));
        return Http.query(list);
    }

    private static String nz(Object value) {
        return value == null ? "" : value.toString();
    }
}
