package dev.appiaworks.shiguchi.graphql;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ScalarSchema;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.config.FieldOverrideConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.TypeRule;
import dev.appiaworks.shiguchi.reflection.GraphQlOptionReader;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Builds the GraphQL SDL schema string from RouteDefinitions at startup.
 *
 * Generates full typed SDL by walking proto Descriptors from each registered
 * ServiceEndpointContext. Handles:
 *  - shiguchi.field annotations: required(!), id(ID scalar), name(alias), omit, description,
 *    deprecation_reason(@deprecated) — read from proto FieldOptions extensions
 *  - YAML fieldOverrides: same semantics, override proto annotations without editing protos
 *  - shiguchi.graphql annotations: description (docstring), deprecation_reason, response pluck
 *    (changes declared return type), request plucks (flattens specific fields as top-level args)
 *  - RESOLVER type: adds field to a named parent type instead of root Query/Mutation
 *  - Relay Connection/Edge types for paged responses (via RelayConnectionTransformer)
 *  - Pagination args injection (first/after/sort) via GraphQlPaginationArgInjector
 *  - Input types for mutations
 *  - Recursive proto message/enum emission with depth limit and duplicate guard
 *  - Well-known proto types mapped to scalars (Timestamp→DateTime, FieldMask→String, etc.), and
 *    the google.type messages shiguchi.json.scalar-types enables (Date→LocalDate,
 *    TimeOfDay→LocalTime, Decimal→Decimal, DateTime→String, Money→JSON)
 */
@Component
@DependsOn({"dynamicRouteLoader", "staticRouteLoader", "restDownstreamRouteLoader"})
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GraphQlSchemaBuilder {

    private static final Logger log = LoggerFactory.getLogger(GraphQlSchemaBuilder.class);
    private static final int MAX_DEPTH = 10;
    private static final Set<String> PAGINATION_FIELD_NAMES = Set.of("page_request", "sort_fields", "read_mask");
    /**
     * Field added to a message that declares no fields (an object or input type needs at least
     * one); same name and type as {@code Query._placeholder}. One underscore: {@code __} is reserved.
     */
    static final String EMPTY_PLACEHOLDER = "_placeholder";

    /** Types whose only field is the placeholder; scratch, rebuilt with each SDL. */
    private final Set<String> placeholderOnlyTypes = new java.util.HashSet<>();

    private final GrpcRouteRegistry registry;
    private final RelayConnectionTransformer relayTransformer;
    private final GraphQlPaginationArgInjector paginationInjector;
    private final GatewayProperties gatewayProperties;
    private final GraphQlOptionReader fieldOptions;
    private volatile RelationshipResolver relationshipResolver;
    private volatile String generatedSdl;
    /** typeName → batch_key JSON field name; populated on each SDL build. */
    private volatile Map<String, String> lastFederationKeys = new LinkedHashMap<>();
    /** "ParentType.fieldName" to cost weight, only for fields with a cost above zero; rebuilt with the SDL. */
    private volatile Map<String, Integer> fieldCosts = Map.of();

    private final java.util.Set<String> warnedFederationFields = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Scratch map filled while an SDL is generated; guarded by {@link #generateSdlWithKeys()}. */
    private final Map<String, Integer> collectingCosts = new LinkedHashMap<>();

    private final List<Relationship> collectingRelationships = new ArrayList<>();
    private volatile List<Relationship> relationships = List.of();
    private final List<RelationshipSource.BatchKey> collectingKeys = new ArrayList<>();
    private volatile List<RelationshipSource.BatchKey> batchKeys = List.of();

    private volatile List<GraphQlContributor> contributors = List.of();

    public GraphQlSchemaBuilder(
            GrpcRouteRegistry registry,
            RelayConnectionTransformer relayTransformer,
            GraphQlPaginationArgInjector paginationInjector,
            GatewayProperties gatewayProperties) {
        this.registry = registry;
        this.relayTransformer = relayTransformer;
        this.paginationInjector = paginationInjector;
        this.gatewayProperties = gatewayProperties;
        this.relationshipResolver = RelationshipResolver.of(gatewayProperties, List.of());
        this.fieldOptions = GraphQlOptionReader.of(
                gatewayProperties != null && gatewayProperties.getGraphql() != null
                        ? gatewayProperties.getGraphql().getCompatOptions()
                        : null);
    }

    /**
     * Extra relationship sources (OpenAPI extensions, user beans). The resolver is the annotations,
     * the YAML, then these, ordered by {@link RelationshipSource.Source} so a later source wins.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRelationshipSources(List<RelationshipSource> sources) {
        this.relationshipResolver = RelationshipResolver.of(gatewayProperties, sources);
    }

    /** The resolver in effect, for readers that must agree with the schema (the console's relationship badges). */
    RelationshipResolver relationshipResolver() {
        return relationshipResolver;
    }

    /** Beans that add fields of their own (the push module's subscription fields); optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContributors(List<GraphQlContributor> contributors) {
        this.contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    @PostConstruct
    public void build() {
        var result = generateSdlWithKeys();
        this.generatedSdl = result.sdl();
        this.lastFederationKeys = result.federationKeys();
        this.fieldCosts = result.fieldCosts();
        this.relationships = result.relationships();
        this.batchKeys = result.batchKeys();
        log.info("Generated GraphQL SDL ({} chars)", generatedSdl.length());
        log.debug("GraphQL SDL:\n{}", generatedSdl);
    }

    public void rebuild() {
        var result = generateSdlWithKeys();
        this.generatedSdl = result.sdl();
        this.lastFederationKeys = result.federationKeys();
        this.fieldCosts = result.fieldCosts();
        this.relationships = result.relationships();
        this.batchKeys = result.batchKeys();
        log.info("Rebuilt GraphQL SDL ({} chars)", generatedSdl.length());
    }

    public String getGeneratedSdl() {
        return generatedSdl;
    }

    /** Returns typeName → batch_key JSON field name for all @key-annotated types. */
    public Map<String, String> getFederationKeys() {
        return lastFederationKeys;
    }

    /**
     * The cost weight of each field that declared one ({@code cost} on a method or a proto field),
     * keyed {@code "ParentType.fieldName"}. Fields without an entry weigh 1.
     */
    public Map<String, Integer> getFieldCosts() {
        return fieldCosts;
    }

    /**
     * The relationships of the current schema: each {@code resolves_type} companion field the SDL
     * got, from the same build as {@link #getGeneratedSdl()}. {@link GatewayDataFetcherRegistrar}
     * wires a {@link GrpcRelationshipDataFetcher} for each, on nested types too.
     */
    public List<Relationship> getRelationships() {
        return relationships;
    }

    /** The batch key of each entity type of the current schema that declares one, with its source. */
    public List<RelationshipSource.BatchKey> getBatchKeys() {
        return batchKeys;
    }

    /**
     * A companion field of an object type that resolves a reference through a DataLoader.
     *
     * @param parentType the GraphQL type that declares the field
     * @param fieldName the companion field's name in the schema
     * @param idJsonName the key of the id in the parent's data (the proto field's JSON name)
     * @param resolvedType the GraphQL type it resolves to (one, or a list of them when {@code groupBy} is set)
     * @param groupBy empty for a {@code resolves_type} reference (one entity by id); for a
     *     {@code resolves_many} field the {@code group_by} of the one-to-many loader that serves its list
     * @param source where the relationship was declared
     * @param idList true when the id field is {@code repeated}: the companion is then a list of the
     *     resolved type, one entity per id (ids the loader does not return are dropped)
     */
    public record Relationship(
            String parentType,
            String fieldName,
            String idJsonName,
            String resolvedType,
            String groupBy,
            RelationshipSource.Source source,
            boolean idList) {

        /** A relationship whose id field is a single value. */
        public Relationship(
                String parentType,
                String fieldName,
                String idJsonName,
                String resolvedType,
                String groupBy,
                RelationshipSource.Source source) {
            this(parentType, fieldName, idJsonName, resolvedType, groupBy, source, false);
        }

        /** A reference to one entity, resolved by id, declared by an annotation. */
        public Relationship(String parentType, String fieldName, String idJsonName, String resolvedType) {
            this(parentType, fieldName, idJsonName, resolvedType, "");
        }

        /** A relationship declared by an annotation. */
        public Relationship(
                String parentType, String fieldName, String idJsonName, String resolvedType, String groupBy) {
            this(parentType, fieldName, idJsonName, resolvedType, groupBy, RelationshipSource.Source.ANNOTATION);
        }

        /** Whether the field is a list of children ({@code resolves_many}), decided here from the parent's descriptor. */
        public boolean many() {
            return groupBy != null && !groupBy.isEmpty();
        }

        /** The DataLoader that serves it: the type's by-id loader, or its one-to-many loader for the group field. */
        public String loaderName() {
            return many() ? GrpcBatchDataLoaderRegistrar.groupedName(resolvedType, groupBy) : resolvedType;
        }
    }

    private record SdlResult(
            String sdl,
            Map<String, String> federationKeys,
            Map<String, Integer> fieldCosts,
            List<Relationship> relationships,
            List<RelationshipSource.BatchKey> batchKeys) {}

    private synchronized SdlResult generateSdlWithKeys() {
        Map<String, String> federationKeys = isFederationEnabled() ? new LinkedHashMap<>() : null;
        collectingCosts.clear();
        placeholderOnlyTypes.clear();
        collectingRelationships.clear();
        collectingKeys.clear();
        String sdl = generateSdl(federationKeys);
        return new SdlResult(
                sdl,
                federationKeys != null ? Map.copyOf(federationKeys) : Map.of(),
                Map.copyOf(collectingCosts),
                List.copyOf(collectingRelationships),
                List.copyOf(collectingKeys));
    }

    // -------------------------------------------------------------------------
    // SDL generation
    // -------------------------------------------------------------------------

    private boolean isFederationEnabled() {
        return gatewayProperties.getGraphql().getFederation().isEnabled();
    }

    private String generateSdl(Map<String, String> federationKeys) {
        // Merge YAML fieldOverrides from all services; later services win on key conflict.
        Map<String, FieldOverrideConfig> yamlOverrides = new HashMap<>();
        for (ServiceDefinition svcDef : gatewayProperties.getServices()) {
            if (svcDef.getFieldOverrides() != null) {
                yamlOverrides.putAll(svcDef.getFieldOverrides());
            }
        }

        StringBuilder queries = new StringBuilder();
        StringBuilder mutations = new StringBuilder();
        StringBuilder subscriptions = new StringBuilder();
        Map<String, String> typeDefs = new LinkedHashMap<>();
        Set<String> emitted = new HashSet<>();
        boolean anyConnection = false;
        boolean anySorting = false;

        // Accumulate RESOLVER field declarations: parentTypeName → list of field decl strings
        Map<String, List<String>> resolverFields = new LinkedHashMap<>();

        // Deduplicated: one route per GraphQL field name. Walking every context's raw routes would
        // emit a field per colliding route, and two fields of the same name in one type make the
        // SDL unparseable — a single duplicate annotation would take the whole schema down.
        for (var entry : registry.graphQlRoutesWithContext()) {
            RouteDefinition route = entry.getKey();
            ServiceEndpointContext ctx = entry.getValue();

            Optional<MethodDescriptor> mdOpt = ctx.lookupMethod(route.getFullServiceName(), route.getMethodName());

            if (mdOpt.isEmpty()) {
                String placeholder = "  " + route.getGraphQlName() + "(input: JSON): JSON\n";
                appendRootField(
                        route.getGraphQlOperationType(),
                        placeholder,
                        queries,
                        mutations,
                        subscriptions,
                        resolverFields,
                        route.getGraphQlParentType());
                recordRootCost(route);
                continue;
            }

            MethodDescriptor md = mdOpt.get();
            Descriptor inputDesc = md.getInputType();
            Descriptor outputDesc = md.getOutputType();

            // --- Return type (honour response pluck) ---
            String returnType;
            String responsePluck = route.getGraphQlResponsePluck();

            if (responsePluck != null && !responsePluck.isEmpty()) {
                // Find the plucked field by JSON name and use its type as the return type
                FieldDescriptor pluckedField = findFieldByJsonName(outputDesc, responsePluck);
                if (pluckedField != null) {
                    returnType =
                            resolveFieldType(pluckedField, typeDefs, emitted, 0, false, yamlOverrides, federationKeys);
                    if (pluckedField.isRepeated()) returnType = "[" + returnType + "!]";
                } else {
                    log.warn(
                            "Response pluck field '{}' not found in {}; falling back to full type",
                            responsePluck,
                            outputDesc.getName());
                    returnType = emitReturnType(outputDesc, typeDefs, emitted, yamlOverrides, federationKeys);
                    if (returnType == null) {
                        anyConnection = true;
                        String connName = relayTransformer.connectionTypeName(outputDesc);
                        typeDefs.putIfAbsent(connName, relayTransformer.generateConnectionSdl(outputDesc));
                        Descriptor nodeDesc = nodeDescriptor(outputDesc);
                        if (nodeDesc != null)
                            emitObjectType(nodeDesc, typeDefs, emitted, 0, yamlOverrides, federationKeys);
                        returnType = connName;
                    }
                }
            } else if (relayTransformer.isConnectionCandidate(outputDesc)) {
                anyConnection = true;
                String connName = relayTransformer.connectionTypeName(outputDesc);
                typeDefs.putIfAbsent(connName, relayTransformer.generateConnectionSdl(outputDesc));
                Descriptor nodeDesc = nodeDescriptor(outputDesc);
                if (nodeDesc != null) emitObjectType(nodeDesc, typeDefs, emitted, 0, yamlOverrides, federationKeys);
                returnType = connName;
            } else if (isWellKnown(outputDesc)) {
                returnType = wellKnownScalar(outputDesc);
            } else {
                emitObjectType(outputDesc, typeDefs, emitted, 0, yamlOverrides, federationKeys);
                returnType = outputDesc.getName();
            }

            // Wrap return type in list for server-streaming non-subscription RPCs.
            // Subscriptions emit individual items, so no list wrapping needed there.
            if (md.isServerStreaming()
                    && route.getGraphQlOperationType() != RouteDefinition.GraphQlOperationType.SUBSCRIPTION) {
                returnType = "[" + returnType + "!]";
            }

            // --- Args ---
            if (paginationInjector.hasSorting(inputDesc)) anySorting = true;
            String args = buildArgs(route, inputDesc, typeDefs, emitted, yamlOverrides, federationKeys);

            // --- FieldRule declaration ---
            String fieldDecl = buildFieldDecl(route, args, returnType);
            appendRootField(
                    route.getGraphQlOperationType(),
                    fieldDecl,
                    queries,
                    mutations,
                    subscriptions,
                    resolverFields,
                    route.getGraphQlParentType());
            recordRootCost(route);
        }

        // Append RESOLVER fields to their parent types (or create new type entries)
        for (Map.Entry<String, List<String>> entry : resolverFields.entrySet()) {
            String parentType = entry.getKey();
            String fieldsBlock = String.join("", entry.getValue());
            if (typeDefs.containsKey(parentType)) {
                String existing = typeDefs.get(parentType);
                // The placeholder only keeps an empty type valid; real resolver fields replace it.
                if (placeholderOnlyTypes.contains(parentType)) {
                    existing = existing.replace("  " + EMPTY_PLACEHOLDER + ": Boolean\n", "");
                }
                // Insert resolver fields before the closing brace
                int closingBrace = existing.lastIndexOf('}');
                typeDefs.put(parentType, existing.substring(0, closingBrace) + fieldsBlock + "}\n");
            } else {
                typeDefs.put(parentType, "type " + parentType + " {\n" + fieldsBlock + "}\n");
            }
        }

        // Fields from outside the routes: each contributor writes into the same buffers.
        for (GraphQlContributor contributor : contributors) {
            contributor.contributeSchema(new GraphQlContributor.Schema() {
                @Override
                public void subscriptionField(String declaration) {
                    String line = declaration.strip();
                    subscriptions.append("  ").append(line).append('\n');
                }

                @Override
                public String objectType(Descriptor descriptor) {
                    if (isWellKnown(descriptor)) return wellKnownScalar(descriptor);
                    emitObjectType(descriptor, typeDefs, emitted, 0, yamlOverrides, federationKeys);
                    return descriptor.getName();
                }

                @Override
                public void type(String name, String sdl) {
                    if (emitted.add(name)) typeDefs.put(name, sdl.endsWith("\n") ? sdl : sdl + "\n");
                }
            });
        }

        // Assemble final SDL
        boolean federation = isFederationEnabled();
        StringBuilder sb = new StringBuilder();
        sb.append("scalar JSON\n");
        sb.append("scalar DateTime\n");
        sb.append("scalar Void\n");
        ProtoJsonMapping mapping = ProtoJsonMapping.current();
        if (mapping.date()) sb.append("scalar LocalDate\n");
        if (mapping.timeOfDay()) sb.append("scalar LocalTime\n");
        if (mapping.decimal()) sb.append("scalar Decimal\n");
        // Codecs that name a scalar of their own; GatewayCodecScalars wires them.
        ProtoJsonMapping.customCodecs().stream()
                .map(c -> c.schema().graphQlName())
                .filter(Objects::nonNull)
                .distinct()
                .forEach(name -> sb.append("scalar ").append(name).append('\n'));
        if (federation) {
            // _Any, _Service, _Entity and Query._service/_entities come from the Apollo transformer DGS applies
            sb.append("scalar _FieldSet\n");
            sb.append("\n");
            sb.append("directive @key(fields: _FieldSet!, resolvable: Boolean) repeatable on OBJECT | INTERFACE\n");
            sb.append("directive @external on FIELD_DEFINITION | OBJECT\n");
            sb.append("directive @requires(fields: _FieldSet!) on FIELD_DEFINITION\n");
            sb.append("directive @provides(fields: _FieldSet!) on FIELD_DEFINITION\n");
            sb.append("directive @extends on OBJECT | INTERFACE\n");
            sb.append("directive @shareable repeatable on FIELD_DEFINITION | OBJECT\n");
        }
        if (gatewayProperties.getGraphql().getIncremental().isEnabled()) {
            sb.append("directive @defer(if: Boolean! = true, label: String) on FRAGMENT_SPREAD | INLINE_FRAGMENT\n");
        }
        if (gatewayProperties.getGraphql().getLive().isEnabled()) {
            sb.append("directive @live(throttleMs: Int) on QUERY\n");
        }
        sb.append("\n");
        if (anyConnection) sb.append(relayTransformer.relayPageInfoSdl()).append("\n");
        if (anySorting) sb.append(paginationInjector.sharedTypeDefs()).append("\n");

        sb.append("type Query {\n");
        if (!queries.isEmpty()) sb.append(queries);
        else sb.append("  ").append(EMPTY_PLACEHOLDER).append(": Boolean\n");
        sb.append("}\n\n");

        if (!mutations.isEmpty())
            sb.append("type Mutation {\n").append(mutations).append("}\n\n");
        if (!subscriptions.isEmpty())
            sb.append("type Subscription {\n").append(subscriptions).append("}\n\n");

        typeDefs.values().forEach(t -> sb.append(t).append("\n"));
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // FieldRule declaration assembly
    // -------------------------------------------------------------------------

    private static String buildFieldDecl(RouteDefinition route, String args, String returnType) {
        var sb = new StringBuilder();

        if (route.getGraphQlDescription() != null
                && !route.getGraphQlDescription().isBlank()) {
            sb.append("  \"").append(route.getGraphQlDescription()).append("\"\n");
        }
        sb.append("  ").append(route.getGraphQlName());
        if (!args.isEmpty()) sb.append("(").append(args).append(")");
        sb.append(": ").append(returnType);
        if (route.getGraphQlDeprecationReason() != null
                && !route.getGraphQlDeprecationReason().isBlank()) {
            sb.append(" @deprecated(reason: \"")
                    .append(route.getGraphQlDeprecationReason())
                    .append("\")");
        }
        for (String directive : route.getGraphQlDirectives()) sb.append(' ').append(directive.strip());
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Federation directives ({@code shareable}, {@code external}, {@code requires}, {@code provides},
     * only when federation is on) then the raw {@code directives}, appended to a field definition.
     */
    private void appendFieldDirectives(
            StringBuilder sb, FieldRule rule, boolean federation, String typeName, String fieldName) {
        if (federation) {
            if (rule.getShareable()) sb.append(" @shareable");
            if (rule.getExternal()) sb.append(" @external");
            if (!rule.getRequires().isBlank()) {
                sb.append(" @requires(fields: \"")
                        .append(validSelectionSet(rule.getRequires(), typeName, fieldName, "requires"))
                        .append("\")");
            }
            if (!rule.getProvides().isBlank()) {
                sb.append(" @provides(fields: \"")
                        .append(validSelectionSet(rule.getProvides(), typeName, fieldName, "provides"))
                        .append("\")");
            }
        } else if (rule.getShareable()
                || rule.getExternal()
                || !rule.getRequires().isBlank()
                || !rule.getProvides().isBlank()) {
            if (warnedFederationFields.add(typeName + "." + fieldName)) {
                log.warn(
                        "{}.{} sets a federation option (shareable/external/requires/provides) but"
                                + " shiguchi.graphql.federation.enabled is false; the option is ignored",
                        typeName,
                        fieldName);
            }
        }
        for (String directive : rule.getDirectivesList()) sb.append(' ').append(directive.strip());
    }

    /** A {@code requires}/{@code provides} value must parse as a selection set. */
    private static String validSelectionSet(String selection, String typeName, String fieldName, String option) {
        String trimmed = selection.strip();
        try {
            graphql.parser.Parser.parse("{ " + trimmed + " }");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "(shiguchi.field)." + option + " of " + typeName + "." + fieldName
                            + " is not a valid selection set: '" + selection + "'",
                    e);
        }
        return trimmed.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Maps the proto or JSON field names of a {@code (shiguchi.type)} key to JSON names; tokens that are
     * not field names (braces of a nested key) pass through.
     */
    private static String resolveKeyFields(Descriptor descriptor, String key) {
        StringBuilder out = new StringBuilder();
        for (String token : key.strip().split("\\s+")) {
            if (out.length() > 0) out.append(' ');
            FieldDescriptor match = descriptor.findFieldByName(token);
            if (match == null) match = findFieldByJsonName(descriptor, token);
            out.append(match != null ? match.getJsonName() : token);
        }
        return out.toString();
    }

    private static String escapeDescription(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // -------------------------------------------------------------------------
    // Arg building
    // -------------------------------------------------------------------------

    private String buildArgs(
            RouteDefinition route,
            Descriptor inputDesc,
            Map<String, String> typeDefs,
            Set<String> emitted,
            Map<String, FieldOverrideConfig> yamlOverrides,
            Map<String, String> federationKeys) {

        List<String> requestPlucks = route.getGraphQlRequestPlucks();

        if (!requestPlucks.isEmpty()) {
            // Expose only the plucked fields as individual top-level args
            StringBuilder args = new StringBuilder();
            for (FieldDescriptor f : inputDesc.getFields()) {
                if (!requestPlucks.contains(f.getName()) && !requestPlucks.contains(f.getJsonName())) continue;
                FieldHints hints = resolveFieldHints(f, yamlOverrides);
                if (hints.omit()) continue;
                String fieldName = hints.name() != null ? hints.name() : f.getJsonName();
                String gqlType = resolveFieldType(f, typeDefs, emitted, 0, false, yamlOverrides, federationKeys);
                if (f.isRepeated()) gqlType = "[" + gqlType + "!]";
                if (hints.required()) gqlType = gqlType + "!";
                if (!args.isEmpty()) args.append(", ");
                args.append(fieldName).append(": ").append(gqlType);
            }
            String paginArgs = paginationInjector.paginationArgs(inputDesc);
            if (!paginArgs.isEmpty()) {
                if (!args.isEmpty()) args.append(", ");
                args.append(paginArgs);
            }
            return args.toString();
        }

        // Standard arg building: filter omitted fields first
        List<FieldDescriptor> userFields = inputDesc.getFields().stream()
                .filter(f -> !PAGINATION_FIELD_NAMES.contains(f.getName()))
                .filter(f -> !resolveFieldHints(f, yamlOverrides).omit())
                .toList();

        StringBuilder args = new StringBuilder();

        if (!userFields.isEmpty()) {
            if (route.getGraphQlOperationType() == RouteDefinition.GraphQlOperationType.MUTATION) {
                String inputTypeName = inputDesc.getName() + "Input";
                emitInputType(inputDesc, inputTypeName, typeDefs, emitted, 0, yamlOverrides);
                args.append("input: ").append(inputTypeName).append("!");
            } else {
                boolean allScalar = userFields.stream().allMatch(f -> f.getType() != FieldDescriptor.Type.MESSAGE);
                if (allScalar && userFields.size() <= 5) {
                    for (FieldDescriptor f : userFields) {
                        FieldHints hints = resolveFieldHints(f, yamlOverrides);
                        String fieldName = hints.name() != null ? hints.name() : f.getJsonName();
                        String gqlType = scalarTypeName(f.getType());
                        if (f.isRepeated()) gqlType = "[" + gqlType + "!]";
                        if (hints.required()) gqlType = gqlType + "!";
                        if (!args.isEmpty()) args.append(", ");
                        args.append(fieldName).append(": ").append(gqlType);
                    }
                } else {
                    String inputTypeName = inputDesc.getName() + "Input";
                    emitInputType(inputDesc, inputTypeName, typeDefs, emitted, 0, yamlOverrides);
                    args.append("input: ").append(inputTypeName);
                }
            }
        }

        String paginArgs = paginationInjector.paginationArgs(inputDesc);
        if (!paginArgs.isEmpty()) {
            if (!args.isEmpty()) args.append(", ");
            args.append(paginArgs);
        }

        return args.toString();
    }

    // -------------------------------------------------------------------------
    // Type emission
    // -------------------------------------------------------------------------

    private void emitObjectType(
            Descriptor descriptor,
            Map<String, String> typeDefs,
            Set<String> emitted,
            int depth,
            Map<String, FieldOverrideConfig> yamlOverrides) {
        emitObjectType(descriptor, typeDefs, emitted, depth, yamlOverrides, null);
    }

    private void emitObjectType(
            Descriptor descriptor,
            Map<String, String> typeDefs,
            Set<String> emitted,
            int depth,
            Map<String, FieldOverrideConfig> yamlOverrides,
            Map<String, String> federationKeys) {

        String typeName = descriptor.getName();
        if (depth > MAX_DEPTH || emitted.contains(typeName) || isWellKnown(descriptor)) return;
        emitted.add(typeName);

        TypeRule typeRule = fieldOptions.typeRule(descriptor);

        // Federation keys: (shiguchi.type).keys, else the field marked batch_key.
        List<String> keySets = new ArrayList<>();
        Optional<RelationshipSource.BatchKey> batchKey = relationshipResolver.batchKey(descriptor);
        batchKey.ifPresent(collectingKeys::add);
        if (federationKeys != null) {
            for (String key : typeRule.getKeysList()) keySets.add(resolveKeyFields(descriptor, key));
            if (keySets.isEmpty()) {
                batchKey.ifPresent(k -> keySets.add(k.jsonName()));
            }
            // _entities resolves by the first single-field key; composite keys are published only.
            if (!typeRule.getUnresolvable()) {
                keySets.stream()
                        .filter(k -> k.indexOf(' ') < 0 && k.indexOf('{') < 0)
                        .findFirst()
                        .or(() -> keySets.stream().findFirst())
                        .ifPresent(k -> federationKeys.put(typeName, k));
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!typeRule.getDescription().isBlank()) {
            sb.append("\"").append(escapeDescription(typeRule.getDescription())).append("\"\n");
        }
        sb.append("type ").append(typeName);
        for (String keySet : keySets) {
            sb.append(" @key(fields: \"").append(keySet).append('"');
            if (typeRule.getUnresolvable()) sb.append(", resolvable: false");
            sb.append(')');
        }
        for (String directive : typeRule.getDirectivesList()) sb.append(' ').append(directive.strip());
        sb.append(" {\n");

        for (FieldDescriptor field : descriptor.getFields()) {
            FieldHints hints = resolveFieldHints(field, yamlOverrides);
            if (hints.omit()) continue;

            String gqlType =
                    resolveFieldType(field, typeDefs, emitted, depth + 1, false, yamlOverrides, federationKeys);
            if (field.isRepeated()) gqlType = "[" + gqlType + "!]";
            if (hints.id()) gqlType = "ID";
            if (hints.required()) gqlType = gqlType + "!";

            String fieldName = hints.name() != null ? hints.name() : field.getJsonName();
            int fieldCost = fieldOptions.fieldRule(field).getCost();
            if (fieldCost > 0) collectingCosts.put(typeName + "." + fieldName, fieldCost);

            if (hints.description() != null) {
                sb.append("  \"").append(hints.description()).append("\"\n");
            }
            sb.append("  ").append(fieldName).append(": ").append(gqlType);
            if (hints.deprecationReason() != null) {
                sb.append(" @deprecated(reason: \"")
                        .append(hints.deprecationReason())
                        .append("\")");
            }
            appendFieldDirectives(sb, fieldOptions.fieldRule(field), federationKeys != null, typeName, fieldName);
            sb.append("\n");

            // Companion resolver fields: resolves_type references and resolves_many lists. A list is
            // nullable [Child!], never [Child!]!, so a failed batch can still null the field
            // instead of failing the parent.
            for (Relationship rel : relationshipResolver.relationships(descriptor, field)) {
                collectingRelationships.add(rel);
                sb.append("  ").append(rel.fieldName()).append(": ");
                if (rel.many() || rel.idList())
                    sb.append('[').append(rel.resolvedType()).append("!]");
                else sb.append(rel.resolvedType());
                sb.append("\n");
            }
        }
        // A message that declares no fields would be a field-less object type, which GraphQL forbids.
        if (descriptor.getFields().isEmpty()) {
            sb.append("  ").append(EMPTY_PLACEHOLDER).append(": Boolean\n");
            placeholderOnlyTypes.add(typeName);
        }
        sb.append("}\n");
        typeDefs.putIfAbsent(typeName, sb.toString());
    }

    private void emitInputType(
            Descriptor descriptor,
            String inputTypeName,
            Map<String, String> typeDefs,
            Set<String> emitted,
            int depth,
            Map<String, FieldOverrideConfig> yamlOverrides) {

        if (depth > MAX_DEPTH || emitted.contains(inputTypeName)) return;
        emitted.add(inputTypeName);

        StringBuilder sb = new StringBuilder();
        sb.append("input ").append(inputTypeName).append(" {\n");

        for (FieldDescriptor field : descriptor.getFields()) {
            if (PAGINATION_FIELD_NAMES.contains(field.getName())) continue;
            FieldHints hints = resolveFieldHints(field, yamlOverrides);
            if (hints.omit()) continue;

            String gqlType = resolveFieldType(field, typeDefs, emitted, depth + 1, true, yamlOverrides);
            if (field.isRepeated()) gqlType = "[" + gqlType + "!]";
            if (hints.id()) gqlType = "ID";
            if (hints.required()) gqlType = gqlType + "!";

            String fieldName = hints.name() != null ? hints.name() : field.getJsonName();
            String defaultVal = hints.defaultValue();

            if (hints.description() != null) {
                sb.append("  \"").append(hints.description()).append("\"\n");
            }
            sb.append("  ").append(fieldName).append(": ").append(gqlType);
            if (defaultVal != null) {
                sb.append(" = ").append(defaultVal);
            }
            if (hints.deprecationReason() != null) {
                sb.append(" @deprecated(reason: \"")
                        .append(hints.deprecationReason())
                        .append("\")");
            }
            sb.append("\n");
        }
        // An empty input object is invalid GraphQL; the optional field is stripped before dispatch.
        if (descriptor.getFields().isEmpty()) {
            sb.append("  ").append(EMPTY_PLACEHOLDER).append(": Boolean\n");
        }
        sb.append("}\n");
        typeDefs.putIfAbsent(inputTypeName, sb.toString());
    }

    private void emitEnumType(EnumDescriptor enumDescriptor, Map<String, String> typeDefs, Set<String> emitted) {

        String enumName = enumDescriptor.getName();
        if (emitted.contains(enumName)) return;
        emitted.add(enumName);

        StringBuilder sb = new StringBuilder();
        sb.append("enum ").append(enumName).append(" {\n");
        enumDescriptor
                .getValues()
                .forEach(v -> sb.append("  ").append(v.getName()).append("\n"));
        sb.append("}\n");
        typeDefs.putIfAbsent(enumName, sb.toString());
    }

    private String resolveFieldType(
            FieldDescriptor field,
            Map<String, String> typeDefs,
            Set<String> emitted,
            int depth,
            boolean isInput,
            Map<String, FieldOverrideConfig> yamlOverrides) {
        return resolveFieldType(field, typeDefs, emitted, depth, isInput, yamlOverrides, null);
    }

    private String resolveFieldType(
            FieldDescriptor field,
            Map<String, String> typeDefs,
            Set<String> emitted,
            int depth,
            boolean isInput,
            Map<String, FieldOverrideConfig> yamlOverrides,
            Map<String, String> federationKeys) {

        return switch (field.getType()) {
            case STRING, BYTES -> "String";
            case INT64, UINT64, FIXED64, SFIXED64, SINT64 -> "String";
            case INT32, UINT32, FIXED32, SFIXED32, SINT32 -> "Int";
            case FLOAT, DOUBLE -> "Float";
            case BOOL -> "Boolean";
            case ENUM -> {
                EnumDescriptor e = field.getEnumType();
                emitEnumType(e, typeDefs, emitted);
                yield e.getName();
            }
            case MESSAGE -> {
                Descriptor msg = field.getMessageType();
                if (isWellKnown(msg)) yield wellKnownScalar(msg);
                if (isInput) {
                    String nestedInputName = msg.getName() + "Input";
                    emitInputType(msg, nestedInputName, typeDefs, emitted, depth, yamlOverrides);
                    yield nestedInputName;
                } else {
                    emitObjectType(msg, typeDefs, emitted, depth, yamlOverrides, federationKeys);
                    yield msg.getName();
                }
            }
            default -> "String";
        };
    }

    // -------------------------------------------------------------------------
    // FieldRule hints (proto annotation + YAML override merge)
    // -------------------------------------------------------------------------

    /**
     * Resolves the effective GraphQL field hints for a proto field by merging:
     *  1. {@code (shiguchi.field)} proto extension (if present), or its {@code graphql.field} (1079)
     *     alias when {@code shiguchi.graphql.compat-options} enables it
     *  2. YAML {@code fieldOverrides} entry keyed by "TypeName.fieldName" (takes precedence)
     */
    private FieldHints resolveFieldHints(FieldDescriptor field, Map<String, FieldOverrideConfig> yamlOverrides) {

        // Proto annotation
        boolean protoRequired = false, protoId = false, protoOmit = false;
        String protoName = null, protoDefault = null, protoDesc = null, protoDepr = null;

        FieldRule ann = fieldOptions.fieldRule(field);
        if (ann != null) {
            protoRequired = ann.getRequired();
            protoId = ann.getId();
            protoOmit = ann.getOmit();
            protoName = ann.getName().isEmpty() ? null : ann.getName();
            protoDefault = ann.getDefault().isEmpty() ? null : ann.getDefault();
            protoDesc = ann.getDescription().isEmpty() ? null : ann.getDescription();
            protoDepr = ann.getDeprecationReason().isEmpty() ? null : ann.getDeprecationReason();
        }

        // YAML override (takes precedence field-by-field)
        String yamlKey = field.getContainingType().getName() + "." + field.getName();
        FieldOverrideConfig override = yamlOverrides.get(yamlKey);
        if (override == null) {
            return new FieldHints(protoRequired, protoId, protoName, protoDefault, protoOmit, protoDesc, protoDepr);
        }
        return new FieldHints(
                override.isRequired() || protoRequired,
                override.isId() || protoId,
                override.getName() != null ? override.getName() : protoName,
                override.getDefaultValue() != null ? override.getDefaultValue() : protoDefault,
                override.isOmit() || protoOmit,
                override.getDescription() != null ? override.getDescription() : protoDesc,
                override.getDeprecationReason() != null ? override.getDeprecationReason() : protoDepr);
    }

    private record FieldHints(
            boolean required,
            boolean id,
            String name,
            String defaultValue,
            boolean omit,
            String description,
            String deprecationReason) {}

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Emit the output descriptor as a return type when NOT using a Relay connection and NOT
     * a well-known type. Returns null when the caller should use the Relay connection path.
     */
    private String emitReturnType(
            Descriptor outputDesc,
            Map<String, String> typeDefs,
            Set<String> emitted,
            Map<String, FieldOverrideConfig> yamlOverrides) {
        return emitReturnType(outputDesc, typeDefs, emitted, yamlOverrides, null);
    }

    private String emitReturnType(
            Descriptor outputDesc,
            Map<String, String> typeDefs,
            Set<String> emitted,
            Map<String, FieldOverrideConfig> yamlOverrides,
            Map<String, String> federationKeys) {
        if (relayTransformer.isConnectionCandidate(outputDesc)) return null;
        if (isWellKnown(outputDesc)) return wellKnownScalar(outputDesc);
        emitObjectType(outputDesc, typeDefs, emitted, 0, yamlOverrides, federationKeys);
        return outputDesc.getName();
    }

    private static FieldDescriptor findFieldByJsonName(Descriptor descriptor, String jsonName) {
        return descriptor.getFields().stream()
                .filter(f -> f.getJsonName().equals(jsonName))
                .findFirst()
                .orElse(null);
    }

    private static Descriptor nodeDescriptor(Descriptor responseDescriptor) {
        return responseDescriptor.getFields().stream()
                .filter(f -> f.isRepeated() && f.getType() == FieldDescriptor.Type.MESSAGE)
                .findFirst()
                .map(FieldDescriptor::getMessageType)
                .orElse(null);
    }

    private void recordRootCost(RouteDefinition route) {
        int cost = route.getGraphQlCost();
        if (cost <= 0) return;
        String parent = switch (route.getGraphQlOperationType()) {
            case QUERY -> "Query";
            case MUTATION -> "Mutation";
            case SUBSCRIPTION -> "Subscription";
            case RESOLVER ->
                route.getGraphQlParentType() != null
                                && !route.getGraphQlParentType().isBlank()
                        ? route.getGraphQlParentType()
                        : "Query";
        };
        collectingCosts.put(parent + "." + route.getGraphQlName(), cost);
    }

    private static void appendRootField(
            RouteDefinition.GraphQlOperationType opType,
            String fieldDecl,
            StringBuilder queries,
            StringBuilder mutations,
            StringBuilder subscriptions,
            Map<String, List<String>> resolverFields,
            String parentType) {
        switch (opType) {
            case QUERY -> queries.append(fieldDecl);
            case MUTATION -> mutations.append(fieldDecl);
            case SUBSCRIPTION -> subscriptions.append(fieldDecl);
            case RESOLVER -> {
                if (parentType != null && !parentType.isBlank()) {
                    resolverFields
                            .computeIfAbsent(parentType, k -> new ArrayList<>())
                            .add(fieldDecl);
                } else {
                    log.warn("RESOLVER route has no parent_type; falling back to Query: {}", fieldDecl.trim());
                    queries.append(fieldDecl);
                }
            }
        }
    }

    private static boolean isWellKnown(Descriptor descriptor) {
        String pkg = descriptor.getFile().getPackage();
        return pkg.startsWith("google.protobuf")
                || pkg.startsWith("google.api")
                || ProtoJsonMapping.isMapped(descriptor);
    }

    private static String wellKnownScalar(Descriptor descriptor) {
        // A message an application's ProtoScalarCodec writes as one value
        ProtoScalarCodec custom = ProtoJsonMapping.customCodecFor(descriptor);
        if (custom != null) return codecScalar(custom.schema());
        // google.type messages the gateway writes as text (shiguchi.json.scalar-types)
        switch (descriptor.getFullName()) {
            case ProtoJsonMapping.DATE:
                return "LocalDate";
            case ProtoJsonMapping.TIME_OF_DAY:
                return "LocalTime";
            case ProtoJsonMapping.DECIMAL:
                return "Decimal";
            case ProtoJsonMapping.DATE_TIME:
                return "String"; // an offset, a zone id or neither: no single scalar fits
            case ProtoJsonMapping.MONEY:
                return "JSON"; // {currency, amount}
            default:
                break;
        }
        return switch (descriptor.getName()) {
            case "Any", "Struct", "Value" -> "JSON";
            case "ListValue" -> "[JSON]";
            case "Timestamp" -> "DateTime";
            case "Empty" -> "Void";
            default -> "String"; // Duration, FieldMask, wrappers
        };
    }

    /**
     * A codec's own scalar when its schema names one, else the built-in scalar of its shape. GraphQL
     * {@code Int} is 32-bit, so an INTEGER is {@code Int} only when its format says int32; any other
     * whole number travels as {@code Float}, which holds a 64-bit value without an error.
     */
    static String codecScalar(ScalarSchema hint) {
        if (hint.graphQlName() != null) return hint.graphQlName();
        return switch (hint.shape()) {
            case STRING -> "String";
            case INTEGER -> "int32".equals(hint.format()) ? "Int" : "Float";
            case NUMBER -> "Float";
            case BOOLEAN -> "Boolean";
            case ANY -> "JSON";
        };
    }

    private static String scalarTypeName(FieldDescriptor.Type type) {
        return switch (type) {
            case INT32, UINT32, FIXED32, SFIXED32, SINT32 -> "Int";
            case FLOAT, DOUBLE -> "Float";
            case BOOL -> "Boolean";
            default -> "String";
        };
    }

    // Kept here for the tests that reach it by reflection; the rule lives in AnnotationRelationshipSource.
    private static String commonTypeNamePrefix(List<String> names) {
        return AnnotationRelationshipSource.commonTypeNamePrefix(names);
    }

    // Legacy helper kept for external callers (e.g. tests)
    public static String protoTypeToGraphQl(FieldDescriptor fd, Set<String> pendingTypes) {
        return switch (fd.getType()) {
            case STRING, BYTES, INT64, UINT64, FIXED64, SFIXED64, SINT64 -> "String";
            case INT32, UINT32, FIXED32, SFIXED32, SINT32 -> "Int";
            case FLOAT, DOUBLE -> "Float";
            case BOOL -> "Boolean";
            case MESSAGE -> {
                String typeName = fd.getMessageType().getName();
                pendingTypes.add(typeName);
                yield typeName;
            }
            case ENUM -> fd.getEnumType().getName();
            default -> "String";
        };
    }
}
