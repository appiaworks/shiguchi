package dev.appiaworks.shiguchi.graphql.scalars;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsRuntimeWiring;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.RuntimeWiring;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Scalars for the {@code google.type} messages {@code shiguchi.json.scalar-types} writes as
 * text: {@code LocalDate} (google.type.Date), {@code LocalTime} (google.type.TimeOfDay) and
 * {@code Decimal} (google.type.Decimal).
 *
 * <p>Values travel as the same ISO strings the REST surface uses, so these scalars check the
 * shape and pass the text through; the gateway's converter builds the proto message and answers
 * a value that passes the shape but not the calendar ({@code 2026-02-30}) with
 * {@code INVALID_ARGUMENT}. {@code LocalDate} admits the partial dates google.type.Date can hold
 * ({@code 2026-09}, {@code 2026}, {@code --09-22}); {@code LocalTime} admits {@code 24:00:00}.
 * {@code Decimal} is a string on the wire, never a JSON number, so no precision is lost; a number
 * literal is accepted on input.
 *
 * <p>Registered unconditionally; the SDL declares each one only while its type is mapped, so an
 * unused scalar never collides with a message of the same name.
 */
@DgsComponent
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayGoogleTypeScalars {

    static final Pattern DATE = Pattern.compile("\\d{4}(-\\d{2}(-\\d{2})?)?|--\\d{2}-\\d{2}");
    static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?");

    @DgsRuntimeWiring
    public RuntimeWiring.Builder addScalars(RuntimeWiring.Builder builder) {
        return builder.scalar(localDate()).scalar(localTime()).scalar(decimal());
    }

    public static GraphQLScalarType localDate() {
        return GraphQLScalarType.newScalar()
                .name("LocalDate")
                .description("ISO-8601 calendar date (google.type.Date): 2026-09-22, or partial 2026-09, 2026, --09-22")
                .coercing(new TextCoercing("LocalDate", s -> DATE.matcher(s).matches(), LocalDate.class))
                .build();
    }

    public static GraphQLScalarType localTime() {
        return GraphQLScalarType.newScalar()
                .name("LocalTime")
                .description("ISO-8601 time of day (google.type.TimeOfDay): 14:03:11.123; 24:00:00 is end of day")
                .coercing(new TextCoercing("LocalTime", s -> TIME.matcher(s).matches(), LocalTime.class))
                .build();
    }

    public static GraphQLScalarType decimal() {
        return GraphQLScalarType.newScalar()
                .name("Decimal")
                .description("Arbitrary-precision decimal as a string (google.type.Decimal): \"12.34\"")
                .coercing(new DecimalCoercing())
                .build();
    }

    /** Text in, text out, shape-checked; a value that has no text form passes through unchanged. */
    private static final class TextCoercing implements Coercing<String, Object> {
        private final String name;
        private final Predicate<String> shape;
        private final Class<?> javaType;

        TextCoercing(String name, Predicate<String> shape, Class<?> javaType) {
            this.name = name;
            this.shape = shape;
            this.javaType = javaType;
        }

        @Override
        public Object serialize(Object result) throws CoercingSerializeException {
            if (result instanceof String || javaType.isInstance(result)) return result.toString();
            // A value the text form cannot carry arrives as its proto3 object form.
            if (result instanceof Map<?, ?> map) return map;
            throw new CoercingSerializeException("Expected a " + name + " string but was '"
                    + result.getClass().getSimpleName() + "'.");
        }

        @Override
        public String parseValue(Object input) throws CoercingParseValueException {
            String text = javaType.isInstance(input) ? input.toString() : input instanceof String s ? s : null;
            if (text == null || !shape.test(text.trim())) {
                throw new CoercingParseValueException("Invalid " + name + " value: " + input);
            }
            return text.trim();
        }

        @Override
        public String parseLiteral(Object input) throws CoercingParseLiteralException {
            if (input instanceof StringValue sv && shape.test(sv.getValue().trim()))
                return sv.getValue().trim();
            throw new CoercingParseLiteralException("Invalid " + name + " literal: " + input);
        }

        @Override
        public Value<?> valueToLiteral(Object input) {
            return StringValue.of(String.valueOf(input));
        }
    }

    private static final class DecimalCoercing implements Coercing<String, String> {

        @Override
        public String serialize(Object result) throws CoercingSerializeException {
            if (result instanceof String s) return s;
            if (result instanceof BigDecimal bd) return bd.toPlainString();
            if (result instanceof Number n) return n.toString();
            throw new CoercingSerializeException(
                    "Expected a Decimal string but was '" + result.getClass().getSimpleName() + "'.");
        }

        @Override
        public String parseValue(Object input) throws CoercingParseValueException {
            String text = input instanceof BigDecimal bd
                    ? bd.toPlainString()
                    : input instanceof Number || input instanceof String
                            ? input.toString().trim()
                            : null;
            if (text == null || !isDecimal(text)) {
                throw new CoercingParseValueException("Invalid Decimal value: " + input);
            }
            return text;
        }

        @Override
        public String parseLiteral(Object input) throws CoercingParseLiteralException {
            String text = switch (input) {
                case StringValue sv -> sv.getValue().trim();
                case IntValue iv -> iv.getValue().toString();
                case FloatValue fv -> fv.getValue().toPlainString();
                default -> null;
            };
            if (text == null || !isDecimal(text)) {
                throw new CoercingParseLiteralException("Invalid Decimal literal: " + input);
            }
            return text;
        }

        @Override
        public Value<?> valueToLiteral(Object input) {
            return StringValue.of(String.valueOf(input));
        }

        private static boolean isDecimal(String s) {
            try {
                new BigDecimal(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}
