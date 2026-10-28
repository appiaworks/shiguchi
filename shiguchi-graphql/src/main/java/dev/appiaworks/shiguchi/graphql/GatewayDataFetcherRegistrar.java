package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.netflix.graphql.dgs.DgsCodeRegistry;
import com.netflix.graphql.dgs.DgsComponent;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.proto.api.GraphQlFieldRule;
import dev.appiaworks.shiguchi.proto.api.GraphQlProto;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import graphql.schema.DataFetcher;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Registers DynamicGraphQlDataFetcher with DGS for every GraphQL field produced by
 * the gateway's route registry, and registers relationship resolvers for fields annotated
 * with resolves_type.
 *
 * DGS discovers @DgsCodeRegistry methods at schema-build time and calls them after
 * @DgsTypeDefinitionRegistry, so all routes are already loaded when this runs.
 * Without this registration DGS uses its default trivial fetcher, which returns null.
 *
 * Relationship resolvers delegate to DGS DataLoaders for batch loading of referenced types.
 */
@DgsComponent
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewayDataFetcherRegistrar {

    private static final Logger log = LoggerFactory.getLogger(GatewayDataFetcherRegistrar.class);

    private final GrpcRouteRegistry registry;
    private final DynamicGraphQlDataFetcher dataFetcher;
    private final GatewayProperties gatewayProperties;

    @Autowired(required = false)
    private FederationDataFetcher federationDataFetcher;

    /** Beans that add fields of their own (the push module's subscription fields); optional. */
    @Autowired(required = false)
    private List<GraphQlContributor> contributors;

    public GatewayDataFetcherRegistrar(
            GrpcRouteRegistry registry, DynamicGraphQlDataFetcher dataFetcher, GatewayProperties gatewayProperties) {
        this.registry = registry;
        this.dataFetcher = dataFetcher;
        this.gatewayProperties = gatewayProperties;
    }

    @DgsCodeRegistry
    public GraphQLCodeRegistry.Builder register(
            GraphQLCodeRegistry.Builder codeRegistryBuilder, TypeDefinitionRegistry typeDefinitionRegistry) {

        int count = 0;
        for (RouteDefinition route : registry.allRoutes()) {
            if (!route.hasGraphQlBinding()) continue;
            String parentType = switch (route.getGraphQlOperationType()) {
                case QUERY -> "Query";
                case MUTATION -> "Mutation";
                case SUBSCRIPTION -> "Subscription";
                case RESOLVER -> {
                    String pt = route.getGraphQlParentType();
                    if (pt == null || pt.isBlank()) {
                        log.warn(
                                "RESOLVER route '{}' has no parent_type; registering on Query", route.getGraphQlName());
                        yield "Query";
                    }
                    yield pt;
                }
            };
            codeRegistryBuilder.dataFetcher(
                    FieldCoordinates.coordinates(parentType, route.getGraphQlName()), dataFetcher);
            count++;
        }
        log.info("Registered {} GraphQL data fetchers", count);

        if (contributors != null) {
            for (GraphQlContributor contributor : contributors) contributor.contributeDataFetchers(codeRegistryBuilder);
        }

        // Register DataLoader-backed resolvers for fields annotated with resolves_type
        int relCount = 0;
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            for (var fd : ctx.getFileDescriptors().values()) {
                for (Descriptor msgType : fd.getMessageTypes()) {
                    for (FieldDescriptor field : msgType.getFields()) {
                        GraphQlFieldRule fieldOpt = field.getOptions().getExtension(GraphQlProto.graphqlField);
                        if (fieldOpt == null || fieldOpt.getResolvesTypeList().isEmpty()) continue;

                        List<String> resolvesTypes = fieldOpt.getResolvesTypeList();
                        String protoName = field.getName();
                        String idFieldJsonName = field.getJsonName();
                        String parentTypeName = msgType.getName();

                        if (resolvesTypes.size() == 1) {
                            // Single type: resolver field name matches the schema builder's logic —
                            // strip "Id" suffix from the JSON name (e.g. "groupId" → "group").
                            String resolverFieldName = idFieldJsonName.endsWith("Id")
                                    ? idFieldJsonName.substring(0, idFieldJsonName.length() - 2)
                                    : idFieldJsonName;
                            String resolvedType = resolvesTypes.get(0);
                            codeRegistryBuilder.dataFetcher(
                                    FieldCoordinates.coordinates(parentTypeName, resolverFieldName),
                                    new GrpcRelationshipDataFetcher(idFieldJsonName, resolvedType));
                            relCount++;
                            log.debug(
                                    "Registered relationship resolver: {}.{} -> DataLoader[{}]",
                                    parentTypeName,
                                    resolverFieldName,
                                    resolvedType);
                        } else {
                            // Multiple types: resolver field name derived from type name.
                            // Strip common CamelCase prefix (e.g. "Book" from BookAuthor/BookAuthorRef),
                            // then lowercase the first character of the remainder.
                            String prefix = commonTypePrefix(resolvesTypes);
                            for (String resolvedType : resolvesTypes) {
                                String stripped = resolvedType.substring(prefix.length());
                                String resolverFieldName =
                                        Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
                                codeRegistryBuilder.dataFetcher(
                                        FieldCoordinates.coordinates(parentTypeName, resolverFieldName),
                                        new GrpcRelationshipDataFetcher(idFieldJsonName, resolvedType));
                                relCount++;
                                log.debug(
                                        "Registered relationship resolver: {}.{} -> DataLoader[{}]",
                                        parentTypeName,
                                        resolverFieldName,
                                        resolvedType);
                            }
                        }
                    }
                }
            }
        }
        if (relCount > 0) log.info("Registered {} GraphQL relationship resolvers", relCount);

        // Federation: wire _service and _entities fetchers
        if (gatewayProperties.getGraphql().getFederation().isEnabled() && federationDataFetcher != null) {
            codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates("Query", "_service"), (DataFetcher<?>)
                    federationDataFetcher::fetchService);
            codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates("Query", "_entities"), (DataFetcher<?>)
                    federationDataFetcher::fetchEntities);
            log.info("Registered Federation data fetchers (_service, _entities)");
        }

        return codeRegistryBuilder;
    }

    /**
     * Returns the longest CamelCase word-boundary prefix shared by all names.
     * E.g. ["BookAuthor","BookAuthorRef"] → "Book"; ["ShopItem","ShopItemRef"] → "Shop";
     *      ["Alpha","Beta"] → ""; ["Paperback","Author"] → "".
     */
    private static String commonTypePrefix(List<String> names) {
        if (names.isEmpty()) return "";
        String first = names.get(0);
        int len = first.length();
        for (String s : names) {
            int shared = 0;
            int max = Math.min(len, s.length());
            while (shared < max && first.charAt(shared) == s.charAt(shared)) shared++;
            len = shared;
        }
        // Find the rightmost CamelCase word-start inside the shared region (skip position 0).
        // "BookAuthor" (shared=10): 'A' at 4 → prefix "Book".
        // "Books" (shared=5, no inner upper): prefix "Books" (full shared region).
        int wordBoundary = -1;
        for (int i = 1; i < len; i++) {
            if (Character.isUpperCase(first.charAt(i))) wordBoundary = i;
        }
        return wordBoundary > 0 ? first.substring(0, wordBoundary) : first.substring(0, len);
    }

    private static String snakeToCamel(String snake) {
        if (snake == null || snake.isBlank()) return snake;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
