package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.regex.Pattern;

/**
 * A glob over topics, as written in JWT claims and role rules: {@code orders:*} matches one path
 * segment, {@code orders:**} matches anything in the namespace, {@code tenant:acme/*} one segment
 * under a prefix, and {@code {var}} is the same as {@code *} (so a pattern can be written like a
 * route template). Segments are separated by {@code /}. A pattern without a colon is a namespace
 * name and matches every topic in it.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class TopicPattern {

    private final String text;
    private final Pattern regex;

    private TopicPattern(String text, Pattern regex) {
        this.text = text;
        this.regex = regex;
    }

    public static TopicPattern parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("topic pattern is required");
        String t = text.indexOf(':') < 0 ? text + ":**" : text;
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '*') {
                if (i + 1 < t.length() && t.charAt(i + 1) == '*') {
                    re.append(".*");
                    i += 2;
                } else {
                    re.append("[^/]*");
                    i++;
                }
            } else if (c == '{') {
                int close = t.indexOf('}', i);
                if (close < 0) throw new IllegalArgumentException("unclosed { in topic pattern '" + text + "'");
                re.append("[^/]*");
                i = close + 1;
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        re.append('$');
        return new TopicPattern(text, Pattern.compile(re.toString()));
    }

    public boolean matches(Topic topic) {
        return regex.matcher(topic.toString()).matches();
    }

    @Override
    public String toString() {
        return text;
    }
}
