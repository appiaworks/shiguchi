package dev.appiaworks.shiguchi.sample.policy;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.GatewayPolicy;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import reactor.core.publisher.Mono;

/**
 * A {@link GatewayPolicy} that keeps its rules in a YAML file and re-reads them when the file
 * changes.
 *
 * <p>Failure behaviour is the point of the sample:
 * <ul>
 *   <li>the first load must succeed, otherwise the constructor throws and the application does not
 *       start (no rules would mean no policy);</li>
 *   <li>a later load that fails (unreadable file, bad YAML, unknown rule type) is logged and
 *       <em>ignored</em>: the previous rules stay in force;</li>
 *   <li>a rule that cannot be evaluated denies the request.</li>
 * </ul>
 * Rules are swapped as one immutable list through an {@link AtomicReference}, so a request sees
 * either the old rules or the new ones, never a mix.
 */
public class FileBackedPolicy implements GatewayPolicy {

    private static final Logger log = LoggerFactory.getLogger(FileBackedPolicy.class);

    /** One rule. Which fields apply depends on {@code type}. */
    record Rule(String id, String type, String tag, String authority, int max, int perSeconds) {}

    private final Path file;
    private final AtomicReference<List<Rule>> rules = new AtomicReference<>();
    private volatile byte[] lastSeen;

    /** Throttle windows by rule id. Kept across reloads so editing the file does not reset them. */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FileBackedPolicy(Path file) {
        this.file = file;
        try {
            byte[] bytes = Files.readAllBytes(file);
            rules.set(parse(bytes));
            lastSeen = bytes;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load policy rules from " + file + "; refusing to start", e);
        }
        log.info("Loaded {} policy rules from {}", rules.get().size(), file);
    }

    /**
     * Re-reads the file when its content differs from what was last seen. Comparing content rather
     * than the modification time avoids missing two edits within one timestamp tick.
     *
     * @return true when new rules were installed
     */
    public boolean reloadIfChanged() {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (Arrays.equals(bytes, lastSeen)) return false;
            // Remember it either way, so a broken file is reported once, not every tick.
            lastSeen = bytes;
            List<Rule> parsed = parse(bytes);
            rules.set(parsed);
            log.info("Reloaded {} policy rules from {}", parsed.size(), file);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unusable policy file {}; keeping the previous rules: {}", file, e.toString());
            return false;
        }
    }

    /** The rule ids now in force, for tests and diagnostics. */
    public List<String> ruleIds() {
        return rules.get().stream().map(Rule::id).toList();
    }

    @Override
    public Mono<Void> check(AuthorizationRequest request) {
        return Mono.defer(() -> {
            RouteDefinition route = request.route();
            Set<String> authorities = authorities(request.authentication());
            for (Rule rule : rules.get()) {
                switch (rule.type()) {
                    case "require-authority" -> {
                        if (route.getTags().contains(rule.tag()) && !authorities.contains(rule.authority())) {
                            return deny(rule);
                        }
                    }
                    case "deny-unsafe" -> {
                        // Idempotency is a fact on the route; isRetryable() also reads a GET as safe.
                        if (authorities.contains(rule.authority()) && !route.isRetryable()) {
                            return deny(rule);
                        }
                    }
                    case "throttle" -> {
                        if (route.getTags().contains(rule.tag())) {
                            Duration wait = windows.computeIfAbsent(rule.id(), id -> new Window())
                                    .acquire(rule.max(), Duration.ofSeconds(rule.perSeconds()));
                            if (wait != null) {
                                log.debug("Throttled by rule {}", rule.id());
                                return Mono.<Void>error(new ThrottledException(wait));
                            }
                        }
                    }
                    default -> {
                        return Mono.<Void>error(new IllegalStateException("unknown rule type " + rule.type()));
                    }
                }
            }
            return Mono.<Void>empty();
        });
    }

    private static Mono<Void> deny(Rule rule) {
        return Mono.error(new AccessDeniedException("denied by policy rule " + rule.id()));
    }

    private static Set<String> authorities(Authentication authentication) {
        if (authentication == null) return Set.of();
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    // ---- parsing -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Rule> parse(byte[] bytes) {
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        if (!(doc instanceof Map<?, ?> root) || !(root.get("rules") instanceof List<?> list)) {
            throw new IllegalArgumentException("expected a top-level `rules:` list");
        }
        List<Rule> parsed = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) throw new IllegalArgumentException("rule is not a mapping: " + item);
            Map<String, Object> r = (Map<String, Object>) m;
            Rule rule = new Rule(
                    text(r, "id", true),
                    text(r, "type", true),
                    text(r, "tag", false),
                    text(r, "authority", false),
                    number(r, "max"),
                    number(r, "per-seconds"));
            validate(rule);
            parsed.add(rule);
        }
        return List.copyOf(parsed);
    }

    private static void validate(Rule rule) {
        switch (rule.type()) {
            case "require-authority" -> require(rule, rule.tag() != null && rule.authority() != null, "tag, authority");
            case "deny-unsafe" -> require(rule, rule.authority() != null, "authority");
            case "throttle" ->
                require(rule, rule.tag() != null && rule.max() > 0 && rule.perSeconds() > 0, "tag, max, per-seconds");
            default -> throw new IllegalArgumentException("rule " + rule.id() + ": unknown type " + rule.type());
        }
    }

    private static void require(Rule rule, boolean ok, String needs) {
        if (!ok) throw new IllegalArgumentException("rule " + rule.id() + " (" + rule.type() + ") needs " + needs);
    }

    private static String text(Map<String, Object> r, String key, boolean required) {
        Object v = r.get(key);
        if (v == null) {
            if (required) throw new IllegalArgumentException("rule is missing `" + key + "`");
            return null;
        }
        return v.toString();
    }

    private static int number(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** A fixed window: {@code max} calls per window, then the caller is told when it resets. */
    private static final class Window {
        private long start;
        private int count;

        /** Returns null when allowed, otherwise how long until the window resets. */
        synchronized Duration acquire(int max, Duration length) {
            long now = System.nanoTime();
            if (start == 0 || now - start >= length.toNanos()) {
                start = now;
                count = 0;
            }
            if (count < max) {
                count++;
                return null;
            }
            return Duration.ofNanos(length.toNanos() - (now - start));
        }
    }
}
