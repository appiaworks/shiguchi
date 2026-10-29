package dev.appiaworks.protobuf.extras;

import dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How the schemas the gateway publishes describe a {@link ProtoScalarCodec}'s JSON value: its
 * shape, and optionally a format, a pattern, a description, an example and a GraphQL scalar name.
 *
 * <pre>{@code
 * ScalarSchema.string()
 *         .withFormat("sku")
 *         .withPattern("[A-Z]{2}-\\d+")
 *         .withDescription("Stock-keeping unit")
 *         .withExample("AB-1")
 *         .withGraphQlName("Sku")
 * }</pre>
 *
 * <p>What each surface makes of it:
 *
 * <table>
 *   <caption>Where each hint goes</caption>
 *   <tr><th>Hint</th><th>OpenAPI</th><th>GraphQL</th><th>Avro</th></tr>
 *   <tr><td>shape</td><td>{@code type}</td><td>{@code String}, {@code Int} or {@code Float}, {@code Boolean}, {@code JSON}</td>
 *       <td>{@code string}, {@code long}, {@code double}, {@code boolean}</td></tr>
 *   <tr><td>format</td><td>{@code format}</td><td>an {@code INTEGER} with format {@code int32} is {@code Int}</td><td>-</td></tr>
 *   <tr><td>pattern</td><td>{@code pattern}</td><td>checked on input by the named scalar</td><td>-</td></tr>
 *   <tr><td>description</td><td>{@code description}</td><td>the named scalar's description</td><td>-</td></tr>
 *   <tr><td>example</td><td>{@code example}</td><td>-</td><td>-</td></tr>
 *   <tr><td>GraphQL name</td><td>-</td><td>a custom scalar of that name instead of a built-in one</td><td>-</td></tr>
 * </table>
 *
 * <p>Hints describe; they do not convert. The codec still reads and writes every value, and a
 * value it refuses is refused however the schema describes it.
 *
 * @param shape the kind of value {@link ProtoScalarCodec#toJson} returns
 * @param format an OpenAPI format such as {@code uuid}, {@code email} or {@code int64}; null for none
 * @param pattern a regular expression the whole text matches, for {@link JsonShape#STRING} only; null for none
 * @param description what the value is, in a sentence; null for none
 * @param example a value of the shape: a {@code String}, a {@code Number} or a {@code Boolean}; for
 *     {@link JsonShape#ANY}, also a {@code Map} or a {@code List}; null for none
 * @param graphQlName the name of a GraphQL custom scalar to declare for this type; null to use a
 *     built-in scalar
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public record ScalarSchema(
        JsonShape shape, String format, String pattern, String description, Object example, String graphQlName) {

    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");

    /** Built-in GraphQL scalars and the ones the gateway's GraphQL schema always or sometimes declares. */
    private static final Set<String> RESERVED_GRAPHQL_NAMES = Set.of(
            "String",
            "Int",
            "Float",
            "Boolean",
            "ID",
            "JSON",
            "DateTime",
            "Void",
            "LocalDate",
            "LocalTime",
            "Decimal",
            "Query",
            "Mutation",
            "Subscription",
            "_Any",
            "_FieldSet",
            "_Service",
            "_Entity");

    public ScalarSchema {
        Objects.requireNonNull(shape, "shape");
        if (format != null && format.isBlank()) throw new IllegalArgumentException("format is blank");
        if (pattern != null) {
            if (shape != JsonShape.STRING) {
                throw new IllegalArgumentException("a pattern applies to STRING values only, not " + shape);
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("pattern is not a valid regular expression: " + pattern, e);
            }
        }
        if (description != null && description.isBlank()) throw new IllegalArgumentException("description is blank");
        if (example != null && !fits(shape, example)) {
            throw new IllegalArgumentException("example " + example + " ("
                    + example.getClass().getSimpleName() + ") is not a " + shape + " value");
        }
        if (example instanceof CharSequence text && pattern != null && !Pattern.matches(pattern, text)) {
            throw new IllegalArgumentException("example '" + text + "' does not match pattern " + pattern);
        }
        if (graphQlName != null) {
            if (!GRAPHQL_NAME.matcher(graphQlName).matches() || graphQlName.startsWith("__")) {
                throw new IllegalArgumentException("not a GraphQL name: '" + graphQlName + "'");
            }
            if (RESERVED_GRAPHQL_NAMES.contains(graphQlName)) {
                throw new IllegalArgumentException(
                        "GraphQL name '" + graphQlName + "' is taken by the gateway's schema");
            }
        }
    }

    /** Just the shape, no other hint. */
    public static ScalarSchema of(JsonShape shape) {
        return new ScalarSchema(shape, null, null, null, null, null);
    }

    public static ScalarSchema string() {
        return of(JsonShape.STRING);
    }

    public static ScalarSchema integer() {
        return of(JsonShape.INTEGER);
    }

    public static ScalarSchema number() {
        return of(JsonShape.NUMBER);
    }

    public static ScalarSchema bool() {
        return of(JsonShape.BOOLEAN);
    }

    public static ScalarSchema any() {
        return of(JsonShape.ANY);
    }

    public ScalarSchema withFormat(String format) {
        return new ScalarSchema(shape, format, pattern, description, example, graphQlName);
    }

    public ScalarSchema withPattern(String pattern) {
        return new ScalarSchema(shape, format, pattern, description, example, graphQlName);
    }

    public ScalarSchema withDescription(String description) {
        return new ScalarSchema(shape, format, pattern, description, example, graphQlName);
    }

    public ScalarSchema withExample(Object example) {
        return new ScalarSchema(shape, format, pattern, description, example, graphQlName);
    }

    public ScalarSchema withGraphQlName(String graphQlName) {
        return new ScalarSchema(shape, format, pattern, description, example, graphQlName);
    }

    /** True when {@code value} is a JSON value of {@code shape}, as {@link ProtoScalarCodec#toJson} returns it. */
    public static boolean fits(JsonShape shape, Object value) {
        return switch (shape) {
            case STRING -> value instanceof CharSequence;
            case INTEGER ->
                value instanceof Byte
                        || value instanceof Short
                        || value instanceof Integer
                        || value instanceof Long
                        || value instanceof java.math.BigInteger;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case ANY ->
                value instanceof CharSequence
                        || value instanceof Number
                        || value instanceof Boolean
                        || value instanceof java.util.Map
                        || value instanceof java.util.List;
        };
    }
}
