package dev.appiaworks.shiguchi.graphql.scalars;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsRuntimeWiring;
import graphql.scalars.ExtendedScalars;
import graphql.schema.idl.RuntimeWiring;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Wires the graphql-java ExtendedScalars.Json scalar (arbitrary JSON object/array/value).
 * Used for google.protobuf.Struct, Any, Value fields in generated SDL.
 */
@DgsComponent
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayJsonScalar {

    @DgsRuntimeWiring
    public RuntimeWiring.Builder addScalar(RuntimeWiring.Builder builder) {
        return builder.scalar(ExtendedScalars.Json);
    }
}
