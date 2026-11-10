package dev.appiaworks.shiguchi.docs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps docs/reference/configuration.md honest. The tables are hand-written (their prose carries
 * units and links), so this walks {@link GatewayProperties} and checks both directions: every
 * property the code binds is named in the reference, and every {@code shiguchi.*} property the
 * reference names exists in the code.
 */
class ConfigurationReferenceDriftTest {

    private static final Path REFERENCE = Path.of("..", "docs", "reference", "configuration.md");

    /** Roots bound by other modules: documented here, checked by those modules' own types. */
    private static final Set<String> OTHER_MODULES = Set.of("shiguchi.bff", "shiguchi.cursus", "shiguchi.validation");

    /** Mirrors a third-party model (OpenAPI SecurityScheme); the reference says "the usual sub-fields". */
    private static final Set<String> OPEN_ENDED = Set.of("shiguchi.openapi.security-schemes");

    private static final Path GUIDES = Path.of("..", "docs", "guides");

    /** Lists documented in tables whose rows are relative to the element, so rows do not repeat the parent. */
    private static final Set<String> RELATIVE_ROWS = Set.of("services", "routes", "sources", "security-schemes");

    @Test
    @DisplayName("GIVEN the bound properties WHEN reading the reference SHOULD name every one of them")
    void GIVEN_bound_properties_WHEN_reading_reference_SHOULD_name_every_one() throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(REFERENCE));
        try (var guides = Files.walk(GUIDES)) {
            for (Path guide : guides.filter(f -> f.toString().endsWith(".md")).toList()) {
                lines.addAll(Files.readAllLines(guide));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String path : leaves()) {
            if (OPEN_ENDED.stream().anyMatch(o -> path.startsWith(o + "."))) {
                continue;
            }
            String[] parts = path.split("\\.");
            String leaf = parts[parts.length - 1];
            String parent = parts.length > 1 && !RELATIVE_ROWS.contains(parts[parts.length - 2])
                    ? parts[parts.length - 2]
                    : null;
            boolean named = lines.stream().anyMatch(l -> mentions(l, leaf) && (parent == null || mentions(l, parent)));
            if (!named) {
                missing.add(path);
            }
        }
        assertThat(missing)
                .as("bound properties that docs/reference/configuration.md never names next to their parent")
                .isEmpty();
    }

    @Test
    @DisplayName("GIVEN the reference WHEN reading its full property names SHOULD find each one in the code")
    void GIVEN_reference_WHEN_reading_full_names_SHOULD_find_each_in_code() throws IOException {
        Set<String> known = new TreeSet<>();
        for (String leaf : leaves()) {
            String[] parts = leaf.split("\\.");
            StringBuilder prefix = new StringBuilder();
            for (String part : parts) {
                prefix.append(prefix.isEmpty() ? "" : ".").append(part);
                known.add(prefix.toString());
            }
        }
        Matcher m = Pattern.compile("`(shiguchi(?:\\.[a-z0-9-]+(?:\\[])?)+)`").matcher(Files.readString(REFERENCE));
        List<String> unknown = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1).replace("[]", "");
            if (OTHER_MODULES.stream().anyMatch(o -> name.equals(o) || name.startsWith(o + "."))) {
                continue;
            }
            if (!known.contains(name)) {
                unknown.add(name);
            }
        }
        assertThat(unknown).as("documented properties the code does not bind").isEmpty();
    }

    private static boolean mentions(String line, String word) {
        return Pattern.compile("(?<![\\w-])" + Pattern.quote(word) + "(?![\\w-])")
                .matcher(line)
                .find();
    }

    /** Every leaf property path under {@code shiguchi}, with list elements as {@code name[]}-less segments. */
    private static Set<String> leaves() {
        Set<String> out = new TreeSet<>();
        walk(GatewayProperties.class, "shiguchi", out, 0);
        return out;
    }

    private static void walk(Class<?> type, String prefix, Set<String> out, int depth) {
        if (depth > 8) {
            return;
        }
        try {
            if (type.isRecord()) {
                for (RecordComponent c : type.getRecordComponents()) {
                    visit(prefix, kebab(c.getName()), c.getGenericType(), out, depth);
                }
                return;
            }
            for (PropertyDescriptor pd :
                    Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
                if (pd.getWriteMethod() != null || pd.getReadMethod() != null && isBean(pd.getPropertyType())) {
                    visit(
                            prefix,
                            kebab(pd.getName()),
                            pd.getReadMethod() != null
                                    ? pd.getReadMethod().getGenericReturnType()
                                    : pd.getPropertyType(),
                            out,
                            depth);
                }
            }
        } catch (java.beans.IntrospectionException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void visit(String prefix, String name, Type type, Set<String> out, int depth) {
        String path = prefix + "." + name;
        Class<?> raw = type instanceof Class<?> c
                ? c
                : type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : Object.class;
        if (isBean(raw)) {
            walk(raw, path, out, depth + 1);
        } else if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > 0) {
            Type element = p.getActualTypeArguments()[p.getActualTypeArguments().length - 1];
            if (element instanceof Class<?> ec && isBean(ec)) {
                walk(ec, path, out, depth + 1);
                return;
            }
            out.add(path);
        } else {
            out.add(path);
        }
    }

    private static boolean isBean(Class<?> c) {
        return c.getName().startsWith("dev.appiaworks.shiguchi.") && !c.isEnum() && !c.isInterface();
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
