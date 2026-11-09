package dev.appiaworks.shiguchi.reflection;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.UnknownFieldSet;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the GraphQL proto options of a method or field: {@code (shiguchi.graphql)} and
 * {@code (shiguchi.field)} first, and, when enabled, the {@code graphql.*} options other gateways
 * use (extension number 1079 on {@code MethodOptions} and {@code FieldOptions}) as aliases of them.
 *
 * <p>Nothing is vendored: the alias is read from the descriptors the upstream already ships. The
 * reader looks for a file of package {@code graphql} among the (transitive) imports of the file
 * that declares the method or field, and uses the extension it declares under number 1079 to
 * decode the option, whether the option arrived as a known extension (generated code with that
 * file on the classpath) or as an unknown field (reflection or a descriptor set). A 1079 without
 * such an import belongs to some other extension and is ignored.
 *
 * <p>The Shiguchi option wins when both are present. While the alias is off, a file carrying
 * {@code graphql.*} options gets one INFO hint; while on, a {@code graphql.*} field with no
 * Shiguchi counterpart is ignored with one WARN per file and field.
 *
 * @since 0.20.0
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.20.0")
public final class GraphQlOptionReader {

    private static final Logger log = LoggerFactory.getLogger(GraphQlOptionReader.class);

    /** The {@code shiguchi.graphql.compat-options} value that turns the {@code graphql.*} alias on. */
    public static final String GRAPHQL_1079 = "graphql-1079";

    /** {@link MethodRule#source()} of a binding read from {@code (shiguchi.graphql)}. */
    public static final String SOURCE_SHIGUCHI = "shiguchi";

    private static final int ALIAS_NUMBER = 1079;
    private static final String ALIAS_PACKAGE = "graphql";
    private static final String METHOD_OPTIONS = "google.protobuf.MethodOptions";
    private static final String FIELD_OPTIONS = "google.protobuf.FieldOptions";

    /** {@code graphql.GraphqlSchema} paths with a {@code GraphQlRule} counterpart. */
    private static final Set<String> MAPPED_METHOD_PATHS = Set.of("type", "name", "request.plucks", "response.pluck");

    /** {@code graphql.GraphqlField} paths with a {@code FieldRule} counterpart. */
    private static final Set<String> MAPPED_FIELD_PATHS = Set.of("required", "name", "default", "omit", "description");

    private final boolean alias1079;
    private final Set<String> hintedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedPaths = ConcurrentHashMap.newKeySet();

    private GraphQlOptionReader(boolean alias1079) {
        this.alias1079 = alias1079;
    }

    /** A reader for the given {@code shiguchi.graphql.compat-options}; null or empty means Shiguchi options only. */
    public static GraphQlOptionReader of(Collection<String> compatOptions) {
        boolean alias = compatOptions != null
                && compatOptions.stream().filter(v -> v != null).anyMatch(v -> GRAPHQL_1079.equalsIgnoreCase(v.trim()));
        return new GraphQlOptionReader(alias);
    }

    /** A reader for the Shiguchi options only. */
    public static GraphQlOptionReader shiguchiOnly() {
        return new GraphQlOptionReader(false);
    }

    /** Whether the {@code graphql.*} (1079) options are read as aliases. */
    public boolean readsGraphql1079() {
        return alias1079;
    }

    /** A method's GraphQL rule and the option family it was read from. */
    public record MethodRule(GraphQlRule rule, String source) {}

    /**
     * The GraphQL rule of a method: {@code (shiguchi.graphql)} when set, else the {@code graphql.schema}
     * alias when enabled and mappable, else empty.
     */
    public Optional<MethodRule> methodRule(MethodDescriptor method) {
        var options = method.getOptions();
        if (options.hasExtension(GraphQlProto.graphql)) {
            return Optional.of(new MethodRule(options.getExtension(GraphQlProto.graphql), SOURCE_SHIGUCHI));
        }
        return alias(options, method.getFile(), METHOD_OPTIONS, method.getFullName())
                .flatMap(schema -> toGraphQlRule(schema, method))
                .map(rule -> new MethodRule(rule, GRAPHQL_1079));
    }

    /**
     * The GraphQL rule of a field: {@code (shiguchi.field)} when set, else the {@code graphql.field}
     * alias when enabled, else the default (empty) rule.
     */
    public FieldRule fieldRule(FieldDescriptor field) {
        var options = field.getOptions();
        if (options.hasExtension(GraphQlProto.field)) {
            return options.getExtension(GraphQlProto.field);
        }
        return alias(options, field.getFile(), FIELD_OPTIONS, field.getFullName())
                .map(alias -> toFieldRule(alias, field))
                .orElse(FieldRule.getDefaultInstance());
    }

    /** The {@code (shiguchi.type)} rule of a message, or the default (empty) rule. */
    public dev.appiaworks.shiguchi.proto.TypeRule typeRule(com.google.protobuf.Descriptors.Descriptor message) {
        var options = message.getOptions();
        return options.hasExtension(GraphQlProto.type)
                ? options.getExtension(GraphQlProto.type)
                : dev.appiaworks.shiguchi.proto.TypeRule.getDefaultInstance();
    }

    // -------------------------------------------------------------------------
    // Locating the 1079 option
    // -------------------------------------------------------------------------

    /**
     * The decoded {@code graphql.*} option on {@code options}, or empty when there is none, the
     * alias is off (after the hint), or the 1079 present is not the {@code graphql} one.
     */
    private Optional<Message> alias(MessageOrBuilder options, FileDescriptor file, String optionsType, String owner) {
        Message found = knownAlias(options);
        if (found == null) {
            UnknownFieldSet.Field raw = options.getUnknownFields().getField(ALIAS_NUMBER);
            if (raw.getLengthDelimitedList().isEmpty()) return Optional.empty();
            FieldDescriptor extension = aliasExtension(file, optionsType);
            if (extension == null) return Optional.empty();
            found = decode(extension.getMessageType(), raw.getLengthDelimitedList(), owner);
            if (found == null) return Optional.empty();
        }
        if (!alias1079) {
            if (hintedFiles.add(file.getName())) {
                log.info(
                        "graphql.* (1079) options found in {} are ignored; set shiguchi.graphql.compat-options={}"
                                + " to read them as aliases of (shiguchi.graphql) and (shiguchi.field)",
                        file.getName(),
                        GRAPHQL_1079);
            }
            return Optional.empty();
        }
        return Optional.of(found);
    }

    /** The 1079 option when the extension was known at parse time (generated code with the graphql file on the classpath). */
    private static Message knownAlias(MessageOrBuilder options) {
        for (Map.Entry<FieldDescriptor, Object> entry : options.getAllFields().entrySet()) {
            FieldDescriptor fd = entry.getKey();
            if (fd.isExtension()
                    && fd.getNumber() == ALIAS_NUMBER
                    && fd.getType() == FieldDescriptor.Type.MESSAGE
                    && !fd.isRepeated()
                    && ALIAS_PACKAGE.equals(fd.getFile().getPackage())
                    && entry.getValue() instanceof Message message) {
                return message;
            }
        }
        return null;
    }

    /**
     * The extension numbered 1079 on {@code optionsType} declared by a package-{@code graphql} file
     * among {@code file} and its transitive imports, or null.
     */
    private static FieldDescriptor aliasExtension(FileDescriptor file, String optionsType) {
        Deque<FileDescriptor> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(file);
        while (!queue.isEmpty()) {
            FileDescriptor current = queue.poll();
            if (!seen.add(current.getName())) continue;
            if (ALIAS_PACKAGE.equals(current.getPackage())) {
                for (FieldDescriptor ext : current.getExtensions()) {
                    if (ext.getNumber() == ALIAS_NUMBER
                            && ext.getType() == FieldDescriptor.Type.MESSAGE
                            && !ext.isRepeated()
                            && optionsType.equals(ext.getContainingType().getFullName())) {
                        return ext;
                    }
                }
            }
            queue.addAll(current.getDependencies());
        }
        return null;
    }

    private static Message decode(Descriptor type, List<ByteString> parts, String owner) {
        try {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(type);
            for (ByteString part : parts) builder.mergeFrom(part);
            return builder.build();
        } catch (InvalidProtocolBufferException e) {
            log.warn(
                    "graphql.* (1079) option on {} could not be decoded as {}: {}",
                    owner,
                    type.getFullName(),
                    e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    /** {@code graphql.GraphqlSchema} to {@code GraphQlRule}; empty when its type has no counterpart (RESOLVER). */
    private Optional<GraphQlRule> toGraphQlRule(Message schema, MethodDescriptor method) {
        String file = method.getFile().getName();
        String owner = method.getFullName();
        warnUnmapped(schema, "", MAPPED_METHOD_PATHS, file, owner);

        GraphQlRule.Builder rule = GraphQlRule.newBuilder();
        String typeName = enumName(schema, "type");
        if (typeName != null) {
            GraphQlRule.Op op = switch (typeName.toUpperCase(Locale.ROOT)) {
                case "QUERY" -> GraphQlRule.Op.QUERY;
                case "MUTATION" -> GraphQlRule.Op.MUTATION;
                case "SUBSCRIPTION" -> GraphQlRule.Op.SUBSCRIPTION;
                default -> null;
            };
            if (op == null) {
                warnOnce(
                        file,
                        owner,
                        "type: " + typeName,
                        "the method keeps its convention-based binding; a resolver needs (shiguchi.graphql) with parent_type");
                return Optional.empty();
            }
            rule.setType(op);
        }
        String name = string(schema, "name");
        if (name != null) rule.setName(name);

        Message request = message(schema, "request");
        if (request != null) {
            for (String pluck : strings(request, "plucks")) rule.addArgFields(pluck);
        }
        Message response = message(schema, "response");
        if (response != null) {
            String pluck = string(response, "pluck");
            if (pluck != null) rule.setPluck(pluck);
        }
        return Optional.of(rule.build());
    }

    /** {@code graphql.GraphqlField} to {@code FieldRule}. */
    private FieldRule toFieldRule(Message alias, FieldDescriptor field) {
        warnUnmapped(alias, "", MAPPED_FIELD_PATHS, field.getFile().getName(), field.getFullName());

        FieldRule.Builder rule = FieldRule.newBuilder();
        rule.setRequired(bool(alias, "required"));
        rule.setOmit(bool(alias, "omit"));
        String name = string(alias, "name");
        if (name != null) rule.setName(name);
        String defaultValue = string(alias, "default");
        if (defaultValue != null) rule.setDefault(defaultValue);
        String description = string(alias, "description");
        if (description != null) rule.setDescription(description);
        return rule.build();
    }

    /** One WARN per file and field path for every set 1079 field outside {@code mapped}. */
    private void warnUnmapped(Message message, String prefix, Set<String> mapped, String file, String owner) {
        for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
            FieldDescriptor fd = entry.getKey();
            String path = prefix + fd.getName();
            if (fd.getType() == FieldDescriptor.Type.MESSAGE
                    && !fd.isRepeated()
                    && entry.getValue() instanceof Message nested) {
                warnUnmapped(nested, path + ".", mapped, file, owner);
            } else if (!mapped.contains(path)) {
                warnOnce(file, owner, path, "it has no (shiguchi.graphql) or (shiguchi.field) counterpart");
            }
        }
    }

    private void warnOnce(String file, String owner, String what, String why) {
        if (warnedPaths.add(file + "#" + owner + "#" + what)) {
            log.warn("graphql.* (1079) option {} on {} ({}) is ignored: {}", what, owner, file, why);
        }
    }

    // -------------------------------------------------------------------------
    // Reading by name, so the upstream file's exact shape need not be known
    // -------------------------------------------------------------------------

    private static FieldDescriptor fieldNamed(Message message, String name) {
        return message.getDescriptorForType().findFieldByName(name);
    }

    private static String string(Message message, String name) {
        FieldDescriptor fd = fieldNamed(message, name);
        if (fd == null || fd.isRepeated() || fd.getType() != FieldDescriptor.Type.STRING) return null;
        String value = (String) message.getField(fd);
        return value.isEmpty() ? null : value;
    }

    private static boolean bool(Message message, String name) {
        FieldDescriptor fd = fieldNamed(message, name);
        if (fd == null || fd.isRepeated() || fd.getType() != FieldDescriptor.Type.BOOL) return false;
        return (Boolean) message.getField(fd);
    }

    private static String enumName(Message message, String name) {
        FieldDescriptor fd = fieldNamed(message, name);
        if (fd == null || fd.isRepeated() || fd.getType() != FieldDescriptor.Type.ENUM) return null;
        return ((EnumValueDescriptor) message.getField(fd)).getName();
    }

    private static Message message(Message message, String name) {
        FieldDescriptor fd = fieldNamed(message, name);
        if (fd == null || fd.isRepeated() || fd.getType() != FieldDescriptor.Type.MESSAGE) return null;
        return message.hasField(fd) ? (Message) message.getField(fd) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Message message, String name) {
        FieldDescriptor fd = fieldNamed(message, name);
        if (fd == null || !fd.isRepeated() || fd.getType() != FieldDescriptor.Type.STRING) return List.of();
        return (List<String>) message.getField(fd);
    }
}
