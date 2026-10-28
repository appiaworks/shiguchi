package dev.appiaworks.shiguchi.graphql;

import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import graphql.GraphQLContext;
import io.grpc.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Builds the outbound gRPC metadata for a GraphQL operation the way the REST surface does:
 * {@link GrpcMetadataPopulator#build} over the inbound request, run inside the request's Reactor
 * context so the caller's principal, credential and forwarded headers are the ones sent.
 */
final class GraphQlMetadata {

    private static final Logger log = LoggerFactory.getLogger(GraphQlMetadata.class);

    private static final Metadata.Key<String> PRINCIPAL_KEY =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private GraphQlMetadata() {}

    /**
     * The metadata for a call made for {@code graphQlContext}'s operation. Without an exchange
     * only the principal can be sent. Without a populator (a loader built by hand) only the
     * inbound Authorization header is copied.
     */
    static Mono<Metadata> build(GraphQLContext graphQlContext, GrpcMetadataPopulator populator) {
        ServerWebExchange exchange = exchange(graphQlContext);
        if (populator == null) {
            return Mono.just(authorizationOnly(exchange));
        }
        Mono<Metadata> metadata = exchange != null
                ? populator.build(exchange.getRequest())
                : ReactiveSecurityContextHolder.getContext()
                        .map(SecurityContext::getAuthentication)
                        .map(auth -> auth != null ? auth.getName() : "anonymous")
                        .defaultIfEmpty("anonymous")
                        .map(principal -> {
                            Metadata md = new Metadata();
                            md.put(PRINCIPAL_KEY, principal);
                            return md;
                        });
        return GraphQlRequestContext.within(graphQlContext, metadata);
    }

    private static ServerWebExchange exchange(GraphQLContext graphQlContext) {
        try {
            return graphQlContext == null ? null : graphQlContext.get(ServerWebExchange.class);
        } catch (Exception e) {
            log.debug("Could not read the exchange from the GraphQL context: {}", e.getMessage());
            return null;
        }
    }

    private static Metadata authorizationOnly(ServerWebExchange exchange) {
        Metadata metadata = new Metadata();
        if (exchange != null) {
            String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (auth != null && !auth.isBlank()) {
                metadata.put(AUTHORIZATION_KEY, auth);
            }
        }
        return metadata;
    }
}
