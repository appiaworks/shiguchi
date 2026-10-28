package dev.appiaworks.shiguchi.routing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A {@code google.api.http} path template, parsed with the grammar {@code google/api/http.proto}
 * defines:
 *
 * <pre>
 * Template = "/" Segments [ Verb ] ;
 * Segments = Segment { "/" Segment } ;
 * Segment  = "*" | "**" | LITERAL | Variable ;
 * Variable = "{" FieldPath [ "=" Segments ] "}" ;
 * FieldPath = IDENT { "." IDENT } ;
 * Verb     = ":" LITERAL ;
 * </pre>
 *
 * <p>So {@code /v1/{name=shelves/*}/books/{book}:publish} is a template, and a request for
 * {@code /v1/shelves/s1/books/b2:publish} binds {@code name=shelves/s1} and {@code book=b2}. The
 * Spring {@code {*rest}} capture-all is also accepted, as {@code {rest=**}}, since configs written
 * before this parser existed use it.
 *
 * <p>Matching works on the raw (still percent-encoded) path, so an encoded {@code %2F} stays inside
 * its segment. Values are decoded the way the spec asks the server to: a one-segment variable is
 * fully decoded, a multi-segment one ({@code {name=shelves/*}}, {@code {path=**}}) is decoded
 * except for {@code %2F}, which would otherwise be indistinguishable from a real separator.
 *
 * <p>Instances are immutable; {@link #of} caches them by template, which is bounded by the number
 * of routes since templates come from descriptors and config, never from requests.
 */
public final class HttpPathTemplate {

    private static final Map<String, HttpPathTemplate> CACHE = new ConcurrentHashMap<>();
    private static final Pattern FIELD_PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private enum Kind {
        LITERAL,
        WILDCARD,
        DEEP_WILDCARD
    }

    private record Segment(Kind kind, String literal) {}

    /** A variable over template segments {@code [start, end)}. */
    private record Variable(String fieldPath, int start, int end) {}

    private final String template;
    private final List<Segment> segments;
    private final List<Variable> variables;
    private final String verb;
    private final boolean deep;

    private HttpPathTemplate(String template, List<Segment> segments, List<Variable> variables, String verb) {
        this.template = template;
        this.segments = segments;
        this.variables = variables;
        this.verb = verb;
        this.deep = !segments.isEmpty() && segments.get(segments.size() - 1).kind() == Kind.DEEP_WILDCARD;
    }

    /**
     * Whether a path holds variables or wildcards. One that does not is matched verbatim and
     * never needs parsing.
     */
    public static boolean isTemplate(String path) {
        return path.indexOf('{') >= 0 || path.indexOf('*') >= 0;
    }

    /** The parsed template, cached. */
    public static HttpPathTemplate of(String template) {
        HttpPathTemplate cached = CACHE.get(template);
        return cached != null ? cached : CACHE.computeIfAbsent(template, HttpPathTemplate::parse);
    }

    /**
     * Parses a template.
     *
     * @throws IllegalArgumentException when the template does not follow the grammar
     */
    public static HttpPathTemplate parse(String template) {
        return new Parser(template).parse();
    }

    public String template() {
        return template;
    }

    /** Field paths of the template's variables, in order. */
    public List<String> variableNames() {
        List<String> names = new ArrayList<>(variables.size());
        for (Variable v : variables) names.add(v.fieldPath());
        return names;
    }

    /** Whether a raw request path matches. */
    public boolean matches(String rawPath) {
        String[] parts = split(rawPath);
        return parts != null && bind(parts);
    }

    /**
     * The decoded variable values for a raw request path, keyed by field path, or null when the
     * path does not match.
     */
    public Map<String, String> match(String rawPath) {
        String[] parts = split(rawPath);
        if (parts == null || !bind(parts)) return null;
        if (variables.isEmpty()) return Collections.emptyMap();
        Map<String, String> values = new LinkedHashMap<>();
        for (Variable v : variables) {
            int end = v.end() == segments.size() && deep ? parts.length : v.end();
            String raw = String.join("/", java.util.Arrays.asList(parts).subList(v.start(), end));
            boolean singleSegment =
                    v.end() - v.start() == 1 && segments.get(v.start()).kind() != Kind.DEEP_WILDCARD;
            values.put(v.fieldPath(), decode(raw, singleSegment));
        }
        return values;
    }

    /**
     * The template as a Spring {@code PathPattern} that matches the same paths, for predicates that
     * only accept that syntax. Exact except for a {@code **} followed by a verb, which PathPattern
     * cannot express: that widens to {@code /**} and the gateway filter's own match decides.
     */
    public String toPathPattern() {
        StringBuilder out = new StringBuilder();
        for (Segment s : segments) {
            out.append('/');
            out.append(
                    switch (s.kind()) {
                        case LITERAL -> s.literal();
                        case WILDCARD -> "*";
                        case DEEP_WILDCARD -> "**";
                    });
        }
        if (out.isEmpty()) out.append('/');
        if (verb != null && !deep) out.append(':').append(verb);
        return out.toString();
    }

    /**
     * The template as an OpenAPI path: each variable becomes {@code {fieldPath}} whatever it spans,
     * which is how grpc-gateway's generator writes {@code {name=shelves/*}} too.
     */
    public String toOpenApiPath() {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int v = 0;
        while (i < segments.size()) {
            out.append('/');
            if (v < variables.size() && variables.get(v).start() == i) {
                out.append('{').append(variables.get(v).fieldPath()).append('}');
                i = variables.get(v).end();
                v++;
                continue;
            }
            Segment s = segments.get(i++);
            out.append(
                    switch (s.kind()) {
                        case LITERAL -> s.literal();
                        case WILDCARD -> "*";
                        case DEEP_WILDCARD -> "**";
                    });
        }
        if (out.isEmpty()) out.append('/');
        if (verb != null) out.append(':').append(verb);
        return out.toString();
    }

    @Override
    public String toString() {
        return template;
    }

    // ---- matching ----

    /** The path's segments with the verb removed, or null when the verb does not match. */
    private String[] split(String rawPath) {
        String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        String[] parts = path.isEmpty() ? new String[0] : path.split("/", -1);
        if (verb == null) return parts;
        if (parts.length == 0) return null;
        String last = parts[parts.length - 1];
        String suffix = ":" + verb;
        if (!last.endsWith(suffix)) return null;
        parts[parts.length - 1] = last.substring(0, last.length() - suffix.length());
        return parts;
    }

    private boolean bind(String[] parts) {
        int fixed = deep ? segments.size() - 1 : segments.size();
        if (deep ? parts.length < fixed : parts.length != fixed) return false;
        for (int i = 0; i < fixed; i++) {
            Segment s = segments.get(i);
            String part = parts[i];
            if (s.kind() == Kind.WILDCARD) {
                if (part.isEmpty()) return false;
            } else if (!part.equals(s.literal()) && !decode(part, true).equals(s.literal())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Percent-decodes as UTF-8. {@code +} stays a plus: form encoding is for query strings, not
     * paths. A malformed escape leaves the value as sent rather than guessing at it.
     */
    static String decode(String raw, boolean decodeSlash) {
        if (raw.indexOf('%') < 0) return raw;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%') {
                if (i + 2 >= raw.length()) return raw;
                int hi = Character.digit(raw.charAt(i + 1), 16);
                int lo = Character.digit(raw.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) return raw;
                int b = (hi << 4) | lo;
                if (b == '/' && !decodeSlash) {
                    bytes.write('%');
                    bytes.write(raw.charAt(i + 1));
                    bytes.write(raw.charAt(i + 2));
                } else {
                    bytes.write(b);
                }
                i += 2;
            } else if (c < 0x80) {
                bytes.write(c);
            } else {
                int cp = raw.codePointAt(i);
                byte[] utf8 = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                bytes.write(utf8, 0, utf8.length);
                i += Character.charCount(cp) - 1;
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return raw;
        }
    }

    // ---- parsing ----

    private static final class Parser {
        private final String template;
        private final String body;
        private final String verb;
        private int pos;
        private final List<Segment> segments = new ArrayList<>();
        private final List<Variable> variables = new ArrayList<>();
        private final Set<String> names = new HashSet<>();

        Parser(String template) {
            if (template == null || template.isEmpty()) throw new IllegalArgumentException("empty path template");
            this.template = template;
            String t = template.startsWith("/") ? template : "/" + template;
            int verbAt = verbIndex(t);
            this.body = verbAt < 0 ? t : t.substring(0, verbAt);
            this.verb = verbAt < 0 ? null : t.substring(verbAt + 1);
            if (verb != null && (verb.isEmpty() || !isLiteral(verb))) fail("invalid verb ':" + verb + "'");
        }

        /** The ':' that starts the verb: outside braces and after the last '/' outside braces. */
        private static int verbIndex(String t) {
            int depth = 0;
            int colon = -1;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                else if (depth == 0 && c == '/') colon = -1;
                else if (depth == 0 && c == ':' && colon < 0) colon = i;
            }
            return colon;
        }

        HttpPathTemplate parse() {
            if (!body.equals("/")) {
                while (pos < body.length()) {
                    expect('/');
                    if (peek() == '{') variable();
                    else segment();
                }
            }
            for (int i = 0; i < segments.size() - 1; i++) {
                if (segments.get(i).kind() == Kind.DEEP_WILDCARD) fail("'**' must be the last segment");
            }
            return new HttpPathTemplate(template, List.copyOf(segments), List.copyOf(variables), verb);
        }

        private void variable() {
            expect('{');
            int close = body.indexOf('}', pos);
            if (close < 0) fail("unclosed variable");
            String inner = body.substring(pos, close);
            pos = close + 1;
            if (inner.indexOf('{') >= 0) fail("nested variable");
            String fieldPath;
            String pattern;
            if (inner.startsWith("*")) { // Spring's {*rest}
                fieldPath = inner.substring(1);
                pattern = "**";
            } else {
                int eq = inner.indexOf('=');
                fieldPath = eq < 0 ? inner : inner.substring(0, eq);
                pattern = eq < 0 ? "*" : inner.substring(eq + 1);
            }
            if (!FIELD_PATH.matcher(fieldPath).matches()) fail("invalid variable name '" + fieldPath + "'");
            if (!names.add(fieldPath)) fail("variable '" + fieldPath + "' appears twice");
            if (pattern.isEmpty()) fail("empty pattern for variable '" + fieldPath + "'");
            int start = segments.size();
            for (String part : pattern.split("/", -1)) segments.add(toSegment(part));
            variables.add(new Variable(fieldPath, start, segments.size()));
        }

        private void segment() {
            int end = body.indexOf('/', pos);
            if (end < 0) end = body.length();
            String part = body.substring(pos, end);
            pos = end;
            segments.add(toSegment(part));
        }

        private Segment toSegment(String part) {
            if (part.equals("*")) return new Segment(Kind.WILDCARD, null);
            if (part.equals("**")) return new Segment(Kind.DEEP_WILDCARD, null);
            if (part.isEmpty()) fail("empty segment");
            if (!isLiteral(part)) fail("invalid segment '" + part + "'");
            return new Segment(Kind.LITERAL, part);
        }

        private static boolean isLiteral(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '{' || c == '}' || c == '*' || c == '/' || c == '=') return false;
            }
            return true;
        }

        private char peek() {
            return pos < body.length() ? body.charAt(pos) : 0;
        }

        private void expect(char c) {
            if (peek() != c) fail("expected '" + c + "' at " + pos);
            pos++;
        }

        private void fail(String why) {
            throw new IllegalArgumentException("Invalid path template '" + template + "': " + why);
        }
    }
}
