package dev.appiaworks.shiguchi.docs;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.UncheckedIOException;
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

/**
 * Compares a {@code @ConfigurationProperties} class with docs/reference/configuration.md. The tables
 * are hand-written (their prose carries units and links), so this checks both directions: every
 * property the code binds is named in the reference or a guide, and every {@code shiguchi.*}
 * property the reference names under the root exists in the code. Shared by the drift tests of the
 * modules that own a properties class (core, bff, cursus, validation).
 */
public final class ConfigurationReferenceChecker {

    private static final String REFERENCE_PATH = "docs/reference/configuration.md";

    /** Mirrors a third-party model (OpenAPI SecurityScheme); the reference says "the usual sub-fields". */
    private static final Set<String> OPEN_ENDED = Set.of("shiguchi.openapi.security-schemes");

    /** Lists documented in tables whose rows are relative to the element, so rows do not repeat the parent. */
    private static final Set<String> RELATIVE_ROWS = Set.of("services", "routes", "sources", "security-schemes");

    private final Class<?> rootType;
    private final String rootPrefix;
    private final Path docs;

    public ConfigurationReferenceChecker(Class<?> rootType, String rootPrefix) {
        this.rootType = rootType;
        this.rootPrefix = rootPrefix;
        this.docs = findDocs();
    }

    /** Bound properties no reference or guide line names next to their parent. */
    public List<String> undocumented() throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(docs.resolve("reference/configuration.md")));
        try (var guides = Files.walk(docs.resolve("guides"))) {
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
        return missing;
    }

    /** Full property names the reference gives under {@code ownedRoots} that the code does not bind. */
    public List<String> unknownDocumented(Set<String> ownedRoots) throws IOException {
        Set<String> known = new TreeSet<>();
        for (String leaf : leaves()) {
            String[] parts = leaf.split("\\.");
            StringBuilder prefix = new StringBuilder();
            for (String part : parts) {
                prefix.append(prefix.isEmpty() ? "" : ".").append(part);
                known.add(prefix.toString());
            }
        }
        Matcher m = Pattern.compile("`(shiguchi(?:\\.[a-z0-9-]+(?:\\[\\])?)+)`")
                .matcher(Files.readString(docs.resolve("reference/configuration.md")));
        List<String> unknown = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1).replace("[]", "");
            if (ownedRoots.stream().noneMatch(o -> name.equals(o) || name.startsWith(o + "."))) {
                continue;
            }
            if (!known.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }

    /** The docs folder, found by walking up from the module directory the test runs in. */
    private static Path findDocs() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(REFERENCE_PATH))) {
                return dir.resolve("docs");
            }
            dir = dir.getParent();
        }
        throw new UncheckedIOException(new IOException(REFERENCE_PATH + " not found above the working directory"));
    }

    /** Every leaf property path under the root, with list elements as {@code name[]}-less segments. */
    private Set<String> leaves() {
        Set<String> out = new TreeSet<>();
        walk(rootType, rootPrefix, out, 0);
        return out;
    }

    private static boolean mentions(String line, String word) {
        return Pattern.compile("(?<![\\w-])" + Pattern.quote(word) + "(?![\\w-])")
                .matcher(line)
                .find();
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
                if (isTransientField(type, pd.getName())) {
                    continue; // internal state behind a Lombok setter, not configuration
                }
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

    private static boolean isTransientField(Class<?> type, String name) {
        try {
            return java.lang.reflect.Modifier.isTransient(
                    type.getDeclaredField(name).getModifiers());
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static boolean isBean(Class<?> c) {
        return c.getName().startsWith("dev.appiaworks.shiguchi.") && !c.isEnum() && !c.isInterface();
    }

    private static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
