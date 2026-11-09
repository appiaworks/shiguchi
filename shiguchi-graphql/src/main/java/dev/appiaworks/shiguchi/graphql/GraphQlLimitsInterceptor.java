package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.LimitsConfig;
import dev.appiaworks.shiguchi.ratelimit.RateLimiter.Decision;
import graphql.ErrorClassification;
import graphql.ErrorType;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.GraphQLSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.support.DefaultExecutionGraphQlResponse;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * Computes the cost of each operation ({@link GraphQlCostCalculator}) and enforces
 * {@code shiguchi.graphql.limits.max-complexity} and the per-caller budget
 * ({@code budget.per-minute}) before any data fetcher runs. An operation over the maximum, or over
 * the caller's budget, is not executed and is answered with a GraphQL error; the budget refusal
 * carries classification {@code RATE_LIMITED}, {@code extensions.retryAfter} and a
 * {@code Retry-After} header. With {@code report-cost} the cost is returned in
 * {@code extensions.cost}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.20.0")
public class GraphQlLimitsInterceptor implements WebGraphQlInterceptor, Ordered {

    /** GraphQLContext key holding the operation's computed cost (an Integer). */
    public static final String COST_KEY = "shiguchi.graphql.cost";

    /** Classification of the error sent when a caller's budget is spent. */
    public enum Classification implements ErrorClassification {
        RATE_LIMITED
    }

    private final ObjectProvider<GraphQlSource> source;
    private final GraphQlCostCalculator calculator;
    private final LimitsConfig limits;
    private final GraphQlCostBudget budget;

    GraphQlLimitsInterceptor(
            ObjectProvider<GraphQlSource> source,
            GraphQlCostCalculator calculator,
            LimitsConfig limits,
            GraphQlCostBudget budget) {
        this.source = source;
        this.calculator = calculator;
        this.limits = limits;
        this.budget = budget;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (!StringUtils.hasText(request.getDocument())) {
            return chain.next(request);
        }
        GraphQlSource graphQlSource = source.getIfAvailable();
        if (graphQlSource == null) {
            return chain.next(request);
        }
        GraphQLSchema schema = graphQlSource.schema();
        Integer cost =
                calculator.calculate(schema, request.getDocument(), request.getOperationName(), request.getVariables());
        if (cost == null) {
            return chain.next(request);
        }
        int max = limits.getMaxComplexity();
        if (max > 0 && cost > max) {
            GraphQLError error = GraphqlErrorBuilder.newError()
                    .message("maximum query complexity exceeded " + cost + " > " + max)
                    .errorType(ErrorType.ValidationError)
                    .build();
            return Mono.just(refused(request, error));
        }
        Mono<Decision> decision = budget == null ? Mono.just(Decision.allow(0)) : budget.charge(request, cost);
        return decision.flatMap(d -> {
            if (!d.allowed()) {
                GraphQLError error = GraphqlErrorBuilder.newError()
                        .message("query cost budget exhausted: retry in " + d.retryAfterSeconds() + " s")
                        .errorType(Classification.RATE_LIMITED)
                        .extensions(Map.of("retryAfter", d.retryAfterSeconds()))
                        .build();
                WebGraphQlResponse response = refused(request, error);
                response.getResponseHeaders().set("Retry-After", Long.toString(d.retryAfterSeconds()));
                return Mono.just(response);
            }
            request.configureExecutionInput((input, builder) ->
                    builder.graphQLContext(c -> c.put(COST_KEY, cost)).build());
            Mono<WebGraphQlResponse> next = chain.next(request);
            return limits.isReportCost() ? next.map(response -> withCost(response, cost)) : next;
        });
    }

    private static WebGraphQlResponse withCost(WebGraphQlResponse response, int cost) {
        return response.transform(builder -> {
            Map<Object, Object> extensions = new LinkedHashMap<>();
            Map<Object, Object> existing = response.getExtensions();
            if (existing != null) extensions.putAll(existing);
            extensions.put("cost", cost);
            builder.extensions(extensions);
        });
    }

    private static WebGraphQlResponse refused(WebGraphQlRequest request, GraphQLError error) {
        ExecutionResult result =
                ExecutionResultImpl.newExecutionResult().addError(error).build();
        return new WebGraphQlResponse(new DefaultExecutionGraphQlResponse(request.toExecutionInput(), result));
    }
}
