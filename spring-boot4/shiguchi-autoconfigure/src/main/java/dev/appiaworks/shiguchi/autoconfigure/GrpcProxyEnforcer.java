package dev.appiaworks.shiguchi.autoconfigure;

import dev.appiaworks.shiguchi.authz.AuthorizationRequest;
import dev.appiaworks.shiguchi.authz.ThrottledException;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer;
import dev.appiaworks.shiguchi.grpc.ProxyCallGuard;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ProtoAccessRules;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.security.JwtPrincipalConverter;
import io.grpc.Metadata;
import io.grpc.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * The {@link ProxyCallGuard} behind {@code shiguchi.grpc.security.mode: ENFORCE}: authenticates the
 * call's bearer token with the gateway's {@link ReactiveJwtDecoder} and JWT-to-authentication
 * converter (the same ones the REST surface uses), then runs the {@link GatewayAuthorizationEnforcer}
 * (the {@code GatewayAuthorizer} and the {@code GatewayPolicy} chain) with surface {@code GRPC} and
 * no request arguments, since the message is not decoded on this path.
 *
 * <p>Bearer only: a session cookie is never read. A route that declares {@code (shiguchi.method).authz}
 * rules is denied, because resolving the rule's id needs the decoded message. Fails startup when no
 * {@link ReactiveJwtDecoder} bean exists.
 */
@Component
@ConditionalOnProperty(name = "shiguchi.grpc.security.mode", havingValue = "ENFORCE")
public class GrpcProxyEnforcer implements ProxyCallGuard {

    private static final Logger log = LoggerFactory.getLogger(GrpcProxyEnforcer.class);

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> RETRY_AFTER_KEY =
            Metadata.Key.of("retry-after", Metadata.ASCII_STRING_MARSHALLER);

    private final ReactiveJwtDecoder decoder;
    private final Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> converter;
    private final GatewayAuthorizationEnforcer enforcer;
    private final GrpcRouteRegistry registry;
    private final Duration timeout;

    @Autowired
    public GrpcProxyEnforcer(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            GatewayAuthorizationEnforcer enforcer,
            ObjectProvider<ReactiveJwtDecoder> decoders,
            ObjectProvider<ReactiveJwtAuthenticationConverter> reactiveConverters,
            ObjectProvider<Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>>> converters) {
        this(
                properties,
                registry,
                enforcer,
                decoders.getIfAvailable(),
                JwtPrincipalConverter.resolve(reactiveConverters, converters));
    }

    GrpcProxyEnforcer(
            GatewayProperties properties,
            GrpcRouteRegistry registry,
            GatewayAuthorizationEnforcer enforcer,
            ReactiveJwtDecoder decoder,
            Converter<Jwt, ? extends Mono<? extends AbstractAuthenticationToken>> converter) {
        if (decoder == null) {
            throw new IllegalStateException("shiguchi.grpc.security.mode is ENFORCE, which requires an OAuth2 JWT "
                    + "decoder to authenticate gRPC callers. Set spring.security.oauth2.resourceserver.jwt.issuer-uri "
                    + "(or jwk-set-uri), or define a ReactiveJwtDecoder bean. Refusing to start a proxy port that "
                    + "would reject every call.");
        }
        this.decoder = decoder;
        this.converter = converter;
        this.registry = registry;
        this.enforcer = enforcer;
        this.timeout = properties.getGrpc().getSecurity().getDecisionTimeout();
    }

    @Override
    public Decision admit(String fullMethodName, Metadata headers) {
        String token = bearer(headers.get(AUTHORIZATION_KEY));
        if (token == null) {
            return Decision.deny(Status.UNAUTHENTICATED.withDescription("A bearer token is required"));
        }

        Authentication auth;
        try {
            auth = authenticate(token);
        } catch (TimeoutException e) {
            return Decision.deny(Status.UNAVAILABLE.withDescription("Authentication timed out"));
        } catch (RuntimeException e) {
            log.debug("gRPC proxy: bearer token rejected: {}", e.toString());
            return Decision.deny(Status.UNAUTHENTICATED.withDescription("The bearer token is invalid"));
        }
        if (auth == null) {
            return Decision.deny(Status.UNAUTHENTICATED.withDescription("The bearer token is invalid"));
        }

        RouteDefinition route = route(fullMethodName);
        if (route != null) {
            if (!route.getAuthzRules().isEmpty()) {
                return Decision.deny(Status.PERMISSION_DENIED.withDescription(
                        "Denied: this method declares authz rules, which the gRPC proxy cannot evaluate"));
            }
            Decision denial = authorize(route, auth, headers);
            if (denial != null) return denial;
        }
        return Decision.allow(auth.getName(), expiry(auth));
    }

    /** Null when the call is allowed. */
    private Decision authorize(RouteDefinition route, Authentication auth, Metadata headers) {
        Mono<Void> decision = enforcer.enforce(
                        route, Map.of(), null, headersOf(headers), AuthorizationRequest.Surface.GRPC)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        try {
            decision.timeout(timeout).block();
            return null;
        } catch (RuntimeException e) {
            Throwable cause = Exceptions.unwrap(e);
            if (cause instanceof ThrottledException throttled) {
                Metadata trailers = new Metadata();
                trailers.put(RETRY_AFTER_KEY, Long.toString(throttled.retryAfterSeconds()));
                return Decision.deny(Status.RESOURCE_EXHAUSTED.withDescription("Throttled"), trailers);
            }
            if (cause instanceof AccessDeniedException) {
                return Decision.deny(Status.PERMISSION_DENIED.withDescription("Denied"));
            }
            if (cause instanceof TimeoutException) {
                log.warn("gRPC proxy: authorization of {} timed out after {}; denying", route.getMethodName(), timeout);
                return Decision.deny(Status.UNAVAILABLE.withDescription("Authorization timed out"));
            }
            log.warn("gRPC proxy: authorization of {} failed; denying", route.getMethodName(), cause);
            return Decision.deny(Status.PERMISSION_DENIED.withDescription("Denied"));
        }
    }

    private Authentication authenticate(String token) throws TimeoutException {
        try {
            return decoder.decode(token)
                    .flatMap(jwt -> {
                        Mono<? extends AbstractAuthenticationToken> converted = converter.convert(jwt);
                        return converted == null ? Mono.empty() : converted;
                    })
                    .cast(Authentication.class)
                    .timeout(timeout)
                    .block();
        } catch (RuntimeException e) {
            Throwable cause = Exceptions.unwrap(e);
            if (cause instanceof TimeoutException timedOut) throw timedOut;
            throw e;
        }
    }

    /** The registered route, or one built from the method's descriptor; null for a method the registry does not know. */
    private RouteDefinition route(String fullMethodName) {
        int slash = fullMethodName.lastIndexOf('/');
        if (slash <= 0) return null;
        String service = fullMethodName.substring(0, slash);
        String method = fullMethodName.substring(slash + 1);
        Optional<RouteDefinition> registered = registry.findBySourceMethod(service, method);
        if (registered.isPresent()) return registered.get();
        ServiceEndpointContext ctx = registry.contextByServiceFqn(service).orElse(null);
        if (ctx == null) return null;
        return ctx.lookupMethod(service, method)
                .map(md -> ProtoAccessRules.apply(
                                RouteDefinition.builder(service, method, methodType(md), ctx.target()), md)
                        .build())
                .orElse(null);
    }

    private static io.grpc.MethodDescriptor.MethodType methodType(com.google.protobuf.Descriptors.MethodDescriptor md) {
        if (md.isClientStreaming() && md.isServerStreaming()) return io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING;
        if (md.isClientStreaming()) return io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING;
        if (md.isServerStreaming()) return io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING;
        return io.grpc.MethodDescriptor.MethodType.UNARY;
    }

    private static String bearer(String header) {
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.length() < 8 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /** ASCII metadata as the authorizer's header map; the enforcer drops {@code authorization} and {@code cookie}. */
    private static Map<String, List<String>> headersOf(Metadata headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String key : headers.keys()) {
            if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) continue;
            Iterable<String> values = headers.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            if (values == null) continue;
            List<String> list = new ArrayList<>();
            values.forEach(list::add);
            out.put(key.toLowerCase(Locale.ROOT), list);
        }
        return out;
    }

    private static Instant expiry(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth)
            return jwtAuth.getToken().getExpiresAt();
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) return jwt.getExpiresAt();
        return auth.getCredentials() instanceof Jwt jwt ? jwt.getExpiresAt() : null;
    }
}
