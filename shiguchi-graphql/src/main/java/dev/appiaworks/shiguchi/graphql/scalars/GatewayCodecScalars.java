package dev.appiaworks.shiguchi.graphql.scalars;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsRuntimeWiring;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec.JsonShape;
import dev.appiaworks.protobuf.extras.ScalarSchema;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import graphql.language.BooleanValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.scalars.ExtendedScalars;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.RuntimeWiring;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * A GraphQL scalar for every {@link ProtoScalarCodec} whose {@link ScalarSchema} names one, so a
 * {@code Sku} field is typed {@code Sku} rather than {@code String}.
 *
 * <p>The scalar checks the shape, and for text the pattern, then passes the value through: the
 * codec converts it, and answers a value it refuses with {@code INVALID_ARGUMENT} as anywhere
 * else. Codecs come from the context and from the mapping installed in the JVM, whichever is
 * ready first; the SDL declares the same names (see {@code GraphQlSchemaBuilder}).
 */
@DgsComponent
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayCodecScalars {

    private final ObjectProvider<ProtoScalarCodec> codecs;

    public GatewayCodecScalars(ObjectProvider<ProtoScalarCodec> codecs) {
        this.codecs = codecs;
    }

    @DgsRuntimeWiring
    public RuntimeWiring.Builder addScalars(RuntimeWiring.Builder builder) {
        Map<String, ScalarSchema> named = new LinkedHashMap<>();
        Stream.concat(codecs.orderedStream(), ProtoJsonMapping.customCodecs().stream())
                .map(ProtoScalarCodec::schema)
                .filter(s -> s.graphQlName() != null)
                .forEach(s -> named.putIfAbsent(s.graphQlName(), s));
        named.values().forEach(s -> builder.scalar(scalar(s)));
        return builder;
    }

    /** The scalar a codec's schema names. */
    public static GraphQLScalarType scalar(ScalarSchema schema) {
        if (schema.graphQlName() == null) throw new IllegalArgumentException("the schema names no GraphQL scalar");
        GraphQLScalarType.Builder scalar = GraphQLScalarType.newScalar()
                .name(schema.graphQlName())
                .coercing(
                        schema.shape() == JsonShape.ANY
                                ? ExtendedScalars.Json.getCoercing()
                                : new ShapeCoercing(schema));
        if (schema.description() != null) scalar.description(schema.description());
        return scalar.build();
    }

    /** A codec's value of one JSON shape, passed through once it has the shape (and the pattern). */
    private static final class ShapeCoercing implements Coercing<Object, Object> {
        private final ScalarSchema schema;
        private final Pattern pattern;

        ShapeCoercing(ScalarSchema schema) {
            this.schema = schema;
            this.pattern = schema.pattern() == null ? null : Pattern.compile(schema.pattern());
        }

        @Override
        public Object serialize(Object result) throws CoercingSerializeException {
            if (result instanceof CharSequence text) result = text.toString();
            if (ScalarSchema.fits(schema.shape(), result)) return result;
            throw new CoercingSerializeException("Expected a " + schema.shape() + " value for " + schema.graphQlName()
                    + " but was '" + result.getClass().getSimpleName() + "'.");
        }

        @Override
        public Object parseValue(Object input) throws CoercingParseValueException {
            Object value = input instanceof CharSequence text ? text.toString() : input;
            if (!accepts(value)) {
                throw new CoercingParseValueException("Invalid " + schema.graphQlName() + " value: " + input);
            }
            return value;
        }

        @Override
        public Object parseLiteral(Object input) throws CoercingParseLiteralException {
            Object value = switch (input) {
                case StringValue sv -> sv.getValue();
                case IntValue iv -> integer(iv.getValue());
                case FloatValue fv -> fv.getValue();
                case BooleanValue bv -> bv.isValue();
                default -> null;
            };
            if (!accepts(value)) {
                throw new CoercingParseLiteralException("Invalid " + schema.graphQlName() + " literal: " + input);
            }
            return value;
        }

        @Override
        public Value<?> valueToLiteral(Object input) {
            return switch (input) {
                case Boolean b -> BooleanValue.of(b);
                case BigDecimal d -> new FloatValue(d);
                case Double d -> new FloatValue(BigDecimal.valueOf(d));
                case Float f -> new FloatValue(new BigDecimal(f.toString()));
                case Number n -> new IntValue(new BigInteger(n.toString()));
                default -> StringValue.of(String.valueOf(input));
            };
        }

        private boolean accepts(Object value) {
            if (value == null || !ScalarSchema.fits(schema.shape(), value)) return false;
            return pattern == null || pattern.matcher((String) value).matches();
        }

        /** A literal whole number as the smallest of int, long and BigInteger that holds it. */
        private static Object integer(BigInteger value) {
            if (value.bitLength() < 32) return value.intValue();
            if (value.bitLength() < 64) return value.longValue();
            return value;
        }
    }
}
