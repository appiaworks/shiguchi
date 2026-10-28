package dev.appiaworks.shiguchi.graphql.scalars;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsRuntimeWiring;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import graphql.schema.idl.RuntimeWiring;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * DateTime scalar for google.protobuf.Timestamp fields.
 * Handles the LinkedHashMap {"seconds": N, "nanos": N} form that Jackson produces
 * when deserializing proto JSON responses.
 */
@DgsComponent
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayDateTimeScalar {

    @DgsRuntimeWiring
    public RuntimeWiring.Builder addScalar(RuntimeWiring.Builder builder) {
        return builder.scalar(GraphQLScalarType.newScalar()
                .name("DateTime")
                .description("RFC 3339 date-time; handles proto Timestamp as {seconds,nanos} map")
                .coercing(new Coercing<OffsetDateTime, String>() {

                    @Override
                    @SuppressWarnings("unchecked")
                    public String serialize(Object input) throws CoercingSerializeException {
                        if (input instanceof Map<?, ?> map) {
                            return toOffsetDateTime((Map<String, Object>) map)
                                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        }
                        if (input instanceof OffsetDateTime dt) {
                            return dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        }
                        if (input instanceof String s) {
                            return s;
                        }
                        throw new CoercingSerializeException(
                                "Expected something we can convert to 'java.time.OffsetDateTime' but was '"
                                        + input.getClass().getSimpleName() + "'.");
                    }

                    @Override
                    public OffsetDateTime parseValue(Object input) throws CoercingParseValueException {
                        try {
                            return OffsetDateTime.parse(input.toString(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        } catch (Exception e) {
                            throw new CoercingParseValueException("Invalid DateTime value: " + input);
                        }
                    }

                    @Override
                    public OffsetDateTime parseLiteral(Object input) throws CoercingParseLiteralException {
                        if (input instanceof StringValue sv) {
                            try {
                                return OffsetDateTime.parse(sv.getValue(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                            } catch (Exception e) {
                                throw new CoercingParseLiteralException("Invalid DateTime literal: " + sv.getValue());
                            }
                        }
                        throw new CoercingParseLiteralException("Expected StringValue for DateTime, got: " + input);
                    }
                })
                .build());
    }

    @SuppressWarnings("unchecked")
    private static OffsetDateTime toOffsetDateTime(Map<String, Object> map) {
        Number seconds = (Number) map.get("seconds");
        Number nanos = (Number) map.getOrDefault("nanos", 0);
        long epochSeconds = seconds != null ? seconds.longValue() : 0L;
        int nanosInt = nanos != null ? nanos.intValue() : 0;
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds, nanosInt), ZoneOffset.UTC);
    }
}
