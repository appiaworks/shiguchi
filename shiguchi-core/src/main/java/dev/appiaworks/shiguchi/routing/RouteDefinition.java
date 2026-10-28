package dev.appiaworks.shiguchi.routing;

import io.grpc.MethodDescriptor;
import java.util.List;

/**
 * Resolved at startup from proto descriptors (static) or gRPC reflection (dynamic).
 * Carries everything the REST and GraphQL layers need to invoke a backend RPC.
 *
 * Use {@link Builder} to construct instances — the old positional constructor is gone.
 */
public class RouteDefinition {

    /** Logical service name as declared in the proto package. */
    private final String fullServiceName;

    private final String methodName;
    private final MethodDescriptor.MethodType methodType;

    /** HTTP method + path template from google.api.http annotation, e.g. GET /v1/inv/types/{id}. */
    private final String httpMethod;

    private final String httpPath;

    /**
     * The {@code body} of the google.api.http rule: the top-level request field the HTTP body
     * maps to, {@code "*"} for the whole request message, or null when the rule does not say
     * (treated as the whole message, the lenient historical behaviour).
     */
    private final String httpBody;

    private final String httpResponseBody;

    /** GraphQL operation name from shiguchi.api.graphql annotation. Null if not annotated. */
    private final String graphQlName;

    private final GraphQlOperationType graphQlOperationType;

    /** Channel target (host:port) resolved from GatewayProperties. */
    private final String channelTarget;

    // ---- enrichment from shiguchi.api.graphql annotation ----

    /** Doc-string emitted as a GraphQL description (""" ... """). Null if not set. */
    private final String graphQlDescription;

    /** When non-null, emits @deprecated(reason: "...") on the root field. */
    private final String graphQlDeprecationReason;

    /**
     * For RESOLVER type: the GraphQL type this field is added to (e.g. "Book").
     * Null for QUERY/MUTATION/SUBSCRIPTION.
     */
    private final String graphQlParentType;

    /**
     * Proto field name (in JSON camelCase) to extract from the gRPC response and return as
     * the GraphQL value instead of the full response object.
     * Null when no pluck is configured.
     */
    private final String graphQlResponsePluck;

    /**
     * Proto field names (in proto snake_case) to expose as individual top-level GraphQL args
     * instead of bundling the request into an Input type.
     * Empty list when not configured.
     */
    private final List<String> graphQlRequestPlucks;

    /** OAuth2/JWT scope names the gateway enforces before dispatching. Empty list = no check. */
    private final List<String> authScopes;

    /**
     * Resolved timeout for calls to the HTTP upstream of a {@code REST_DOWNSTREAM} operation, or
     * null when none was resolved (gRPC routes). See {@code ServiceDefinition.effectiveHttpTimeout}.
     */
    private final java.time.Duration upstreamTimeout;

    /**
     * Object-level authorization rules from {@code (shiguchi.api.method).authz}. Empty = no object
     * check. Carried for a {@code GatewayAuthorizer} to consume; the gateway does not evaluate
     * them itself, so they are inert when no authorizer reads them.
     */
    private final List<AuthzRuleDefinition> authzRules;

    /** When > 0, adds Cache-Control: max-age=N to REST responses for this method. */
    private final int cacheTtlSeconds;

    /** When non-empty, this method is a loader for the named GraphQL type. */
    private final String loaderFor;

    /** Proto field name (snake_case) in the request that carries the list of IDs. */
    private final String loaderIdsField;

    /** Proto field name (snake_case) in the response that carries the result objects. */
    private final String loaderResultField;

    /**
     * When non-null, this route is served by merging several backend RPCs rather than dispatching
     * to one. Null for every ordinary route.
     */
    private final dev.appiaworks.shiguchi.merge.MergeSpec mergeSpec;

    /** How the gateway reaches the backend for this route. {@link Transport#GRPC} for every proto route. */
    private final Transport transport;

    /**
     * Base URI of the HTTP backend for a {@link Transport#HTTP} route ({@code scheme://authority}),
     * used as the Spring Cloud Gateway route URI. Null for gRPC routes.
     */
    private final String upstreamUri;

    /**
     * The method's declared {@code idempotency_level}, from proto {@code MethodOptions}.
     * {@link IdempotencyLevel#IDEMPOTENCY_UNKNOWN} when the proto does not say, which is the
     * common case — protos rarely declare it.
     */
    private final IdempotencyLevel idempotencyLevel;

    /**
     * What a proto method declares about repeating it, mirroring
     * {@code google.protobuf.MethodOptions.IdempotencyLevel}.
     */
    public enum IdempotencyLevel {
        /** Nothing declared: assume repeating the call may have a second effect. */
        IDEMPOTENCY_UNKNOWN,
        /** A pure read. Repeating it changes nothing. */
        NO_SIDE_EFFECTS,
        /** Has effects, but repeating it lands in the same state. */
        IDEMPOTENT
    }

    private RouteDefinition(Builder b) {
        this.transport = b.upstreamUri != null ? Transport.HTTP : Transport.GRPC;
        this.idempotencyLevel = b.idempotencyLevel != null ? b.idempotencyLevel : IdempotencyLevel.IDEMPOTENCY_UNKNOWN;
        this.upstreamUri = b.upstreamUri;
        this.fullServiceName = b.fullServiceName;
        this.methodName = b.methodName;
        this.methodType = b.methodType;
        this.httpMethod = b.httpMethod;
        this.httpPath = b.httpPath;
        this.httpBody = b.httpBody;
        this.httpResponseBody = b.httpResponseBody;
        this.graphQlName = b.graphQlName;
        this.graphQlOperationType = b.graphQlOperationType;
        this.channelTarget = b.channelTarget;
        this.graphQlDescription = b.graphQlDescription;
        this.graphQlDeprecationReason = b.graphQlDeprecationReason;
        this.graphQlParentType = b.graphQlParentType;
        this.graphQlResponsePluck = b.graphQlResponsePluck;
        this.graphQlRequestPlucks = b.graphQlRequestPlucks != null ? List.copyOf(b.graphQlRequestPlucks) : List.of();
        this.authScopes = b.authScopes != null ? List.copyOf(b.authScopes) : List.of();
        this.upstreamTimeout = b.upstreamTimeout;
        this.authzRules = b.authzRules != null ? List.copyOf(b.authzRules) : List.of();
        this.cacheTtlSeconds = b.cacheTtlSeconds;
        this.loaderFor = b.loaderFor != null ? b.loaderFor : "";
        this.loaderIdsField = b.loaderIdsField != null ? b.loaderIdsField : "";
        this.loaderResultField = b.loaderResultField != null ? b.loaderResultField : "";
        this.mergeSpec = b.mergeSpec;
    }

    public static Builder builder(
            String fullServiceName, String methodName, MethodDescriptor.MethodType methodType, String channelTarget) {
        return new Builder(fullServiceName, methodName, methodType, channelTarget);
    }

    // ---- accessors ----

    public String getFullServiceName() {
        return fullServiceName;
    }

    public IdempotencyLevel getIdempotencyLevel() {
        return idempotencyLevel;
    }

    /**
     * Whether repeating this call is safe, and therefore whether the gateway may retry it when
     * {@code retry.retry-non-idempotent} is left off.
     *
     * <p>Safe means the proto declared {@code NO_SIDE_EFFECTS} or {@code IDEMPOTENT}, or — for a
     * REST route whose proto said nothing — the method is bound to a safe HTTP verb. A
     * {@code google.api.http} {@code get} binding is a strong statement that the call is a read;
     * {@code post}/{@code patch} equally says it is not.
     */
    public boolean isRetryable() {
        if (idempotencyLevel == IdempotencyLevel.NO_SIDE_EFFECTS || idempotencyLevel == IdempotencyLevel.IDEMPOTENT) {
            return true;
        }
        return "GET".equals(httpMethod) || "HEAD".equals(httpMethod) || "OPTIONS".equals(httpMethod);
    }

    public String getMethodName() {
        return methodName;
    }

    public MethodDescriptor.MethodType getMethodType() {
        return methodType;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getHttpPath() {
        return httpPath;
    }

    /** Request field the HTTP body maps to, {@code "*"} for the whole message, or null. */
    public String getHttpBody() {
        return httpBody;
    }

    /**
     * The single response field to send as the HTTP body ({@code google.api.http}'s
     * {@code response_body}), or null to send the whole message.
     *
     * <p>Lets an RPC keep a wrapper message — which proto style and forward compatibility both
     * want — while the REST surface returns the thing itself: {@code ListItems} answering with
     * {@code {"items": [...]}} over gRPC can serve a bare JSON array to HTTP callers.
     */
    public String getHttpResponseBody() {
        return httpResponseBody;
    }

    public String getGraphQlName() {
        return graphQlName;
    }

    public GraphQlOperationType getGraphQlOperationType() {
        return graphQlOperationType;
    }

    public String getChannelTarget() {
        return channelTarget;
    }

    public String getGraphQlDescription() {
        return graphQlDescription;
    }

    public String getGraphQlDeprecationReason() {
        return graphQlDeprecationReason;
    }

    public String getGraphQlParentType() {
        return graphQlParentType;
    }

    public String getGraphQlResponsePluck() {
        return graphQlResponsePluck;
    }

    public List<String> getGraphQlRequestPlucks() {
        return graphQlRequestPlucks;
    }

    public List<String> getAuthScopes() {
        return authScopes;
    }

    /** Resolved HTTP upstream timeout, or null when none was resolved. */
    public java.time.Duration getUpstreamTimeout() {
        return upstreamTimeout;
    }

    public List<AuthzRuleDefinition> getAuthzRules() {
        return authzRules;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public String getLoaderFor() {
        return loaderFor;
    }

    public String getLoaderIdsField() {
        return loaderIdsField;
    }

    public String getLoaderResultField() {
        return loaderResultField;
    }

    public boolean isLoader() {
        return !loaderFor.isEmpty();
    }

    public dev.appiaworks.shiguchi.merge.MergeSpec getMergeSpec() {
        return mergeSpec;
    }

    /** True when this route fans out to several backends instead of dispatching to one. */
    public boolean isMerge() {
        return mergeSpec != null;
    }

    public Transport getTransport() {
        return transport;
    }

    public String getUpstreamUri() {
        return upstreamUri;
    }

    /**
     * True when the request is proxied to an HTTP backend as-is instead of transcoded to gRPC.
     * The REST filter lets such a route fall through to Spring Cloud Gateway's own routing.
     */
    public boolean isPassthrough() {
        return transport == Transport.HTTP;
    }

    public boolean hasRestBinding() {
        return httpMethod != null && httpPath != null;
    }

    public boolean hasGraphQlBinding() {
        return graphQlName != null;
    }

    public enum GraphQlOperationType {
        QUERY,
        MUTATION,
        SUBSCRIPTION,
        RESOLVER
    }

    /** Backend protocol behind a route. */
    public enum Transport {
        /** Transcoded to a gRPC call over the service's {@code ManagedChannel}. */
        GRPC,
        /** Proxied unchanged to an HTTP backend ({@code REST_DOWNSTREAM}). */
        HTTP
    }

    // -------------------------------------------------------------------------

    public static class Builder {
        private final String fullServiceName;
        private final String methodName;
        private final MethodDescriptor.MethodType methodType;
        private final String channelTarget;

        private String httpMethod;
        private String httpPath;
        private String httpBody;
        private String httpResponseBody;
        private String graphQlName;
        private GraphQlOperationType graphQlOperationType;
        private String graphQlDescription;
        private String graphQlDeprecationReason;
        private String graphQlParentType;
        private String graphQlResponsePluck;
        private List<String> graphQlRequestPlucks;
        private List<String> authScopes;
        private java.time.Duration upstreamTimeout;
        private List<AuthzRuleDefinition> authzRules;
        private int cacheTtlSeconds;
        private String loaderFor;
        private String loaderIdsField;
        private String loaderResultField;
        private dev.appiaworks.shiguchi.merge.MergeSpec mergeSpec;
        private String upstreamUri;
        private IdempotencyLevel idempotencyLevel;

        private Builder(
                String fullServiceName,
                String methodName,
                MethodDescriptor.MethodType methodType,
                String channelTarget) {
            this.fullServiceName = fullServiceName;
            this.methodName = methodName;
            this.methodType = methodType;
            this.channelTarget = channelTarget;
        }

        public Builder httpBinding(String method, String path) {
            return httpBinding(method, path, null);
        }

        /** REST binding with the rule's {@code body}: a top-level field name, {@code "*"}, or null. */
        public Builder httpBinding(String method, String path, String body) {
            this.httpMethod = method;
            this.httpPath = path;
            this.httpBody = body;
            return this;
        }

        /** The rule's {@code response_body}: one top-level response field, or null for all of it. */
        public Builder httpResponseBody(String responseBody) {
            this.httpResponseBody = responseBody;
            return this;
        }

        /**
         * Drops the GraphQL binding. Used for the routes an {@code additional_bindings} entry adds:
         * they keep everything else (auth scopes, cache TTL, loader hints) but the GraphQL field
         * belongs to the primary binding alone.
         */
        public Builder withoutGraphQl() {
            this.graphQlName = null;
            this.graphQlOperationType = null;
            return this;
        }

        /**
         * Marks the route as an HTTP passthrough to {@code baseUri} ({@code scheme://authority}).
         * Sets {@link Transport#HTTP}; the gateway proxies matching requests instead of transcoding.
         */
        public Builder httpUpstream(String baseUri) {
            this.upstreamUri = baseUri;
            return this;
        }

        public Builder graphQl(String name, GraphQlOperationType type) {
            this.graphQlName = name;
            this.graphQlOperationType = type;
            return this;
        }

        public Builder description(String description) {
            this.graphQlDescription = description;
            return this;
        }

        public Builder deprecationReason(String reason) {
            this.graphQlDeprecationReason = reason;
            return this;
        }

        public Builder parentType(String parentType) {
            this.graphQlParentType = parentType;
            return this;
        }

        public Builder responsePluck(String pluck) {
            this.graphQlResponsePluck = pluck;
            return this;
        }

        public Builder requestPlucks(List<String> plucks) {
            this.graphQlRequestPlucks = plucks;
            return this;
        }

        public Builder authScopes(List<String> scopes) {
            this.authScopes = scopes;
            return this;
        }

        public Builder upstreamTimeout(java.time.Duration timeout) {
            this.upstreamTimeout = timeout;
            return this;
        }

        public Builder authzRules(List<AuthzRuleDefinition> rules) {
            this.authzRules = rules;
            return this;
        }

        public Builder cacheTtlSeconds(int ttl) {
            this.cacheTtlSeconds = ttl;
            return this;
        }

        public Builder loaderFor(String type, String idsField, String resultField) {
            this.loaderFor = type;
            this.loaderIdsField = idsField;
            this.loaderResultField = resultField;
            return this;
        }

        public Builder mergeSpec(dev.appiaworks.shiguchi.merge.MergeSpec spec) {
            this.mergeSpec = spec;
            return this;
        }

        /** The method's declared {@code idempotency_level}; decides whether a retry may repeat it. */
        public Builder idempotencyLevel(IdempotencyLevel level) {
            this.idempotencyLevel = level;
            return this;
        }

        public RouteDefinition build() {
            return new RouteDefinition(this);
        }
    }
}
