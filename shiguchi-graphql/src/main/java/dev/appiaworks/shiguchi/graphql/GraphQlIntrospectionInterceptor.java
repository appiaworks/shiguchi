package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.GraphQlConfig;
import graphql.introspection.Introspection;
import org.springframework.core.Ordered;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

/**
 * Applies {@code shiguchi.graphql.introspection} per request. When introspection is refused
 * (everyone with {@code DISABLED}, anonymous callers with {@code AUTHENTICATED}) graphql-java
 * answers an operation that selects {@code __schema} or {@code __type} with an error and does not
 * run it; all other operations are untouched.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.20.0")
public class GraphQlIntrospectionInterceptor implements WebGraphQlInterceptor, Ordered {

    private final GraphQlConfig.Introspection mode;

    GraphQlIntrospectionInterceptor(GraphQlConfig.Introspection mode) {
        this.mode = mode;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return switch (mode) {
            case ENABLED -> chain.next(request);
            case DISABLED -> chain.next(disable(request));
            case AUTHENTICATED -> authenticated().flatMap(ok -> chain.next(ok ? request : disable(request)));
        };
    }

    private static WebGraphQlRequest disable(WebGraphQlRequest request) {
        request.configureExecutionInput(
                (input, builder) -> builder.graphQLContext(c -> c.put(Introspection.INTROSPECTION_DISABLED, true))
                        .build());
        return request;
    }

    private static Mono<Boolean> authenticated() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(a -> a.getName() != null && !"anonymousUser".equals(a.getName()))
                .defaultIfEmpty(false);
    }
}
