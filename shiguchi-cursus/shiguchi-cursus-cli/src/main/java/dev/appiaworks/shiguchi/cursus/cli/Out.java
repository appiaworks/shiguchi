package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** What a command prints: NDJSON lines on stdout, diagnostics on stderr, a tiny table for {@code --pretty}. */
final class Out {

    private final PrintWriter out;
    private final PrintWriter err;
    private final boolean pretty;
    private final boolean quiet;

    Out(PrintWriter out, PrintWriter err, boolean pretty, boolean quiet) {
        this.out = out;
        this.err = err;
        this.pretty = pretty;
        this.quiet = quiet;
    }

    boolean pretty() {
        return pretty;
    }

    /** One compact JSON document on its own line, flushed so a pipe sees it at once. */
    synchronized void json(JsonNode node) {
        out.println(node.toString());
        out.flush();
    }

    synchronized void text(String line) {
        out.println(line);
        out.flush();
    }

    /** A diagnostic on stderr; silent under {@code --quiet}. */
    synchronized void info(String message) {
        if (quiet) return;
        err.println("cursus: " + message);
        err.flush();
    }

    /** The elements of {@code items}: NDJSON lines, or a table of {@code columns} (JSON pointers) under --pretty. */
    synchronized void rows(JsonNode items, String... columns) {
        if (items == null || !items.isArray()) return;
        if (!pretty) {
            for (JsonNode item : items) json(item);
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (JsonNode item : items) {
            String[] row = new String[columns.length];
            for (int i = 0; i < columns.length; i++) row[i] = cell(item.at(columns[i]));
            rows.add(row);
        }
        String[] header = new String[columns.length];
        for (int i = 0; i < columns.length; i++) {
            String c = columns[i];
            header[i] = c.substring(c.lastIndexOf('/') + 1).toUpperCase();
        }
        table(header, rows);
    }

    /** Prints a left-aligned table with a header row. */
    synchronized void table(String[] header, List<String[]> rows) {
        int[] width = new int[header.length];
        for (int i = 0; i < header.length; i++) width[i] = header[i].length();
        for (String[] row : rows) for (int i = 0; i < row.length; i++) width[i] = Math.max(width[i], row[i].length());
        out.println(line(header, width));
        for (String[] row : rows) out.println(line(row, width));
        out.flush();
    }

    private static String line(String[] cells, int[] width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append("  ");
            sb.append(cells[i]);
            if (i < cells.length - 1) sb.append(" ".repeat(width[i] - cells[i].length()));
        }
        return sb.toString();
    }

    private static String cell(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "-";
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(n -> parts.add(n.asText()));
            return String.join(",", parts);
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
