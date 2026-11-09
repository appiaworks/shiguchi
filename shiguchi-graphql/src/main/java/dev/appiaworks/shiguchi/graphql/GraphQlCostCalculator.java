package dev.appiaworks.shiguchi.graphql;

import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.FieldComplexityEnvironment;
import graphql.analysis.QueryComplexityCalculator;
import graphql.execution.CoercedVariables;
import graphql.parser.Parser;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes the cost of an operation. A field costs its declared weight (1 when none), a list
 * field that many times over: its own {@code first} argument, else the enclosing field's, else
 * {@code default-list-size}. The cost of the fields selected under a field is added, not
 * multiplied.
 */
final class GraphQlCostCalculator {

    private static final Logger log = LoggerFactory.getLogger(GraphQlCostCalculator.class);
    private static final String FIRST = "first";

    private final Supplier<Map<String, Integer>> weights;
    private final int defaultListSize;

    GraphQlCostCalculator(Supplier<Map<String, Integer>> weights, int defaultListSize) {
        this.weights = weights;
        this.defaultListSize = Math.max(1, defaultListSize);
    }

    /** The cost, or null when the document cannot be analysed (the engine then reports the error). */
    Integer calculate(GraphQLSchema schema, String document, String operationName, Map<String, Object> variables) {
        try {
            Map<String, Integer> costs = weights.get();
            FieldComplexityCalculator perField = (env, childCost) -> field(costs, env, childCost);
            return QueryComplexityCalculator.newCalculator()
                    .schema(schema)
                    .fieldComplexityCalculator(perField)
                    .document(Parser.parse(document))
                    .operationName(operationName)
                    .variables(CoercedVariables.of(variables == null ? Map.of() : variables))
                    .build()
                    .calculate();
        } catch (RuntimeException e) {
            log.debug("GraphQL cost not computed: {}", e.toString());
            return null;
        }
    }

    private int field(Map<String, Integer> costs, FieldComplexityEnvironment env, int childCost) {
        String parent = env.getParentType() == null ? "" : env.getParentType().getName();
        long weight = costs.getOrDefault(parent + "." + env.getField().getName(), 1);
        long multiplier = isList(env) ? listSize(env) : 1;
        return (int) Math.min(Integer.MAX_VALUE, weight * multiplier + childCost);
    }

    private static boolean isList(FieldComplexityEnvironment env) {
        if (env.getFieldDefinition() == null) return false;
        GraphQLType type = env.getFieldDefinition().getType();
        if (type instanceof GraphQLNonNull nonNull) type = nonNull.getWrappedType();
        return type instanceof GraphQLList;
    }

    private int listSize(FieldComplexityEnvironment env) {
        Integer own = first(env);
        if (own != null) return own;
        FieldComplexityEnvironment parent = env.getParentEnvironment();
        Integer inherited = parent == null ? null : first(parent);
        return inherited != null ? inherited : defaultListSize;
    }

    private static Integer first(FieldComplexityEnvironment env) {
        Object value = env.getArguments() == null ? null : env.getArguments().get(FIRST);
        return value instanceof Number n && n.intValue() > 0 ? n.intValue() : null;
    }
}
