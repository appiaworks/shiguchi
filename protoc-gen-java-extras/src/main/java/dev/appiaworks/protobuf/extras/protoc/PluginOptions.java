package dev.appiaworks.protobuf.extras.protoc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The plugin's parameter string: comma-separated {@code key=value} options, the form protoc
 * hands over from {@code --javaextras_opt}.
 *
 * <pre>
 * disable=google.type.Decimal
 * map=acme.Sku;com.acme.SkuCode;com.acme.Skus.toCode;com.acme.Skus.toProto
 * </pre>
 *
 * <ul>
 *   <li>{@code disable=<proto type>} — no accessors for fields of that type.
 *   <li>{@code map=<proto type>;<java type>;<toJava>;<fromJava>} — accessors for a message type
 *       of your own. The two converters are static methods named as {@code package.Class.method}.
 *       Mapping a type that has a default replaces the default.
 *   <li>{@code json=off} — no {@code writeJson} / {@code mergeJson} methods on the messages (on by default).
 * </ul>
 *
 * <p>Converters are method names, not expressions, so none of the separators can occur inside a
 * value. The parts of a mapping are separated by semicolons rather than colons because protoc
 * itself splits {@code --javaextras_out=<options>:<directory>} at a colon.
 */
record PluginOptions(Set<String> disabled, List<TypeMapping> custom, boolean json) {

    static PluginOptions parse(String parameter) {
        Set<String> disabled = new LinkedHashSet<>();
        List<TypeMapping> custom = new ArrayList<>();
        boolean json = true;
        if (parameter == null || parameter.isBlank()) return new PluginOptions(disabled, custom, json);

        for (String raw : parameter.split(",")) {
            String option = raw.trim();
            if (option.isEmpty()) continue;
            int eq = option.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("Option '" + option + "' is not key=value");
            String key = option.substring(0, eq).trim();
            String value = option.substring(eq + 1).trim();
            switch (key) {
                case "disable" -> disabled.add(protoType(value, option));
                case "map" -> custom.add(mapping(value, option));
                case "json" -> json = onOff(value, option);
                default ->
                    throw new IllegalArgumentException(
                            "Unknown option '" + key + "'; known options are disable, map and json");
            }
        }
        return new PluginOptions(disabled, custom, json);
    }

    private static boolean onOff(String value, String option) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException("Option '" + option + "' must be on or off");
        };
    }

    private static TypeMapping mapping(String value, String option) {
        String[] parts = value.split(";");
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "Option '" + option + "' must be map=<proto type>;<java type>;<toJava method>;<fromJava method>");
        }
        String javaType = parts[1].trim();
        String toJava = parts[2].trim();
        String fromJava = parts[3].trim();
        requireQualifiedName(javaType, "java type", option);
        requireQualifiedName(toJava, "toJava method", option);
        requireQualifiedName(fromJava, "fromJava method", option);
        return TypeMapping.custom(protoType(parts[0], option), javaType, toJava, fromJava);
    }

    /** Accepts {@code .google.type.Date} and {@code google.type.Date}. */
    private static String protoType(String value, String option) {
        String name = value.trim();
        if (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) throw new IllegalArgumentException("Option '" + option + "' names no proto type");
        return name;
    }

    private static void requireQualifiedName(String name, String what, String option) {
        if (!name.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
            throw new IllegalArgumentException("Option '" + option + "': " + what + " '" + name
                    + "' must be a fully qualified name such as com.acme.Skus.toCode, not an expression");
        }
    }

    /** The defaults, minus what is disabled or replaced, plus the custom mappings. */
    List<TypeMapping> resolve() {
        Set<String> replaced = new LinkedHashSet<>();
        for (TypeMapping mapping : custom) replaced.add(mapping.protoType());
        List<TypeMapping> result = new ArrayList<>();
        for (TypeMapping mapping : TypeMapping.DEFAULTS) {
            if (!disabled.contains(mapping.protoType()) && !replaced.contains(mapping.protoType())) result.add(mapping);
        }
        for (TypeMapping mapping : custom) {
            if (!disabled.contains(mapping.protoType())) result.add(mapping);
        }
        return result;
    }
}
