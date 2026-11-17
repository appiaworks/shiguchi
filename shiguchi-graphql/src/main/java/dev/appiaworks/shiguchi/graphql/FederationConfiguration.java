package dev.appiaworks.shiguchi.graphql;

import com.netflix.graphql.dgs.DgsFederationResolver;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.TypeResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Apollo Federation subgraph protocol onto the generated GraphQL schema when
 * {@code graphql.federation.enabled} is set, so a router can compose this gateway with other
 * subgraphs instead of treating it as a standalone endpoint.
 */
@Configuration
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class FederationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.federation", name = "enabled", havingValue = "true")
    public FederationDataFetcher federationDataFetcher(
            GraphQlSchemaBuilder schemaBuilder, BatchLoaderPipeline pipeline, GrpcMetadataPopulator metadataPopulator) {
        return new FederationDataFetcher(schemaBuilder, pipeline, metadataPopulator);
    }

    /**
     * Hands the entity protocol to DGS, whose Apollo transformer adds {@code _Any}, {@code _Service},
     * the {@code _Entity} union and {@code Query._service}/{@code _entities} to the schema (and
     * replaces whatever fetchers sit on those fields). Entities come back tagged with their
     * {@code __typename} so the union resolves to the right object type.
     */
    @Bean
    @ConditionalOnProperty(prefix = "shiguchi.graphql.federation", name = "enabled", havingValue = "true")
    public DgsFederationResolver gatewayFederationResolver(FederationDataFetcher fetcher) {
        return new DgsFederationResolver() {
            @Override
            public DataFetcher<Object> entitiesFetcher() {
                return env -> {
                    List<Map<String, Object>> reps = env.getArgument("representations");
                    return fetcher.fetchEntities(env).thenApply(result -> tagged(result, reps));
                };
            }

            @Override
            public TypeResolver typeResolver() {
                return env -> env.getObject() instanceof Map<?, ?> m && m.get("__typename") instanceof String name
                        ? env.getSchema().getObjectType(name)
                        : null;
            }
        };
    }

    private static DataFetcherResult<List<Object>> tagged(
            DataFetcherResult<List<Object>> result, List<Map<String, Object>> representations) {
        List<Object> entities = result.getData();
        List<Object> out = new ArrayList<>(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            Object entity = entities.get(i);
            if (entity instanceof Map<?, ?> map && representations != null) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                copy.put("__typename", representations.get(i).get("__typename"));
                out.add(copy);
            } else {
                out.add(entity);
            }
        }
        return DataFetcherResult.<List<Object>>newResult()
                .data(out)
                .errors(result.getErrors())
                .build();
    }
}
