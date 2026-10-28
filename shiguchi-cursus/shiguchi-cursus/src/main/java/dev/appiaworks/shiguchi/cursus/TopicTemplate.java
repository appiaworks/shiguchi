package dev.appiaworks.shiguchi.cursus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code pattern} of a {@code (shiguchi.api.topic)} option: {@code namespace:template},
 * the template naming request fields in braces ({@code orders:{order_id}},
 * {@code tenant:{tenant_id}/{stream}}). Matches a topic and yields the variables' values; a
 * variable matches one path segment (no {@code /}); a source's variable may be dotted ({@code header.tenant}).
 */
public final class TopicTemplate {

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.]*)}");

    private final String text;
    private final String namespace;
    private final List<String> variables;
    private final Pattern regex;

    private TopicTemplate(String text, String namespace, List<String> variables, Pattern regex) {
        this.text = text;
        this.namespace = namespace;
        this.variables = List.copyOf(variables);
        this.regex = regex;
    }

    public static TopicTemplate parse(String text) {
        return parse(text, true);
    }

    /** A source's target: like {@link #parse}, but a fixed topic without variables is allowed. */
    public static TopicTemplate parseTarget(String text) {
        return parse(text, false);
    }

    private static TopicTemplate parse(String text, boolean variablesRequired) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("pattern is required");
        int colon = text.indexOf(':');
        if (colon <= 0 || colon == text.length() - 1) {
            throw new IllegalArgumentException("pattern '" + text + "' must be namespace:template");
        }
        String namespace = text.substring(0, colon);
        new Topic(namespace, "x");
        String template = text.substring(colon + 1);
        if (variablesRequired && template.indexOf('{') < 0) {
            throw new IllegalArgumentException("pattern '" + text + "' names no {variable}: a fixed topic needs none");
        }
        if (!variablesRequired && template.indexOf('{') < 0) new Topic(namespace, template);
        List<String> variables = new ArrayList<>();
        StringBuilder re = new StringBuilder("^");
        Matcher m = VARIABLE.matcher(template);
        int at = 0;
        while (m.find()) {
            re.append(Pattern.quote(template.substring(at, m.start())));
            String name = m.group(1);
            if (variables.contains(name)) {
                throw new IllegalArgumentException("pattern '" + text + "' names {" + name + "} twice");
            }
            variables.add(name);
            re.append("([^/]+)");
            at = m.end();
        }
        String tail = template.substring(at);
        if (tail.indexOf('{') >= 0 || tail.indexOf('}') >= 0) {
            throw new IllegalArgumentException("pattern '" + text + "' has an unbalanced brace");
        }
        re.append(Pattern.quote(tail)).append('$');
        return new TopicTemplate(text, namespace, variables, Pattern.compile(re.toString()));
    }

    public String namespace() {
        return namespace;
    }

    /** The request fields the template names, in order. */
    public List<String> variables() {
        return variables;
    }

    /** The variables' values when {@code topic} matches, in template order; null when it does not. */
    public Map<String, String> match(Topic topic) {
        if (!topic.namespace().equals(namespace)) return null;
        Matcher m = regex.matcher(topic.name());
        if (!m.matches()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < variables.size(); i++) out.put(variables.get(i), m.group(i + 1));
        return out;
    }

    /**
     * The topic for one record: every variable replaced by {@code lookup}'s value. Fails when a
     * value is missing, blank or contains {@code /}, or when the result is not a valid topic name.
     */
    public Topic expand(java.util.function.Function<String, String> lookup) {
        String template = text.substring(namespace.length() + 1);
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (m.find()) {
            out.append(template, at, m.start());
            String name = m.group(1);
            String value = lookup.apply(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("{" + name + "} has no value for " + text);
            }
            if (value.indexOf('/') >= 0) {
                throw new IllegalArgumentException(
                        "{" + name + "} = '" + value + "' contains '/' (one segment per variable)");
            }
            out.append(value);
            at = m.end();
        }
        out.append(template, at, template.length());
        return new Topic(namespace, out.toString());
    }

    @Override
    public String toString() {
        return text;
    }
}
