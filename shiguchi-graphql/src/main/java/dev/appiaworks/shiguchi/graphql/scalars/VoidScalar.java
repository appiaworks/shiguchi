package dev.appiaworks.shiguchi.graphql.scalars;

import com.netflix.graphql.dgs.DgsScalar;
import graphql.language.NullValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Represents mutation responses that return nothing (google.protobuf.Empty output type).
 * Serializes to null; parses any input as null.
 */
@DgsScalar(name = "Void")
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class VoidScalar implements Coercing<Void, String> {

    @Override
    public String serialize(Object dataFetcherResult) throws CoercingSerializeException {
        return null;
    }

    @Override
    public Void parseValue(Object input) throws CoercingParseValueException {
        return null;
    }

    @Override
    public Void parseLiteral(Object input) throws CoercingParseLiteralException {
        return null;
    }

    @Override
    public Value<?> valueToLiteral(Object input) {
        return NullValue.of();
    }
}
