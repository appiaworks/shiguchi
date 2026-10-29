package dev.appiaworks.shiguchi.gateway;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import io.grpc.Metadata;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Builds gRPC call Metadata from the current reactive SecurityContext and HTTP headers.
 *
 * <p>Always propagates:
 * <ul>
 *   <li>{@code x-principal}: authenticated principal name (or "anonymous")</li>
 *   <li>{@code authorization}: Bearer token from the Authorization header (OAuth2/JWT passthrough)</li>
 * </ul>
 *
 * <p>Additionally copies every header listed in {@code shiguchi.client.forward-headers}
 * from the inbound HTTP request onto the outbound metadata (case-insensitive match, metadata
 * keys lowercased) â€” correlation IDs, tenant IDs, custom trace headers. {@code x-principal} and
 * {@code authorization} are never copied that way, even when listed: the gateway sets both itself,
 * and gRPC's {@code Metadata.get} returns the last value, so a copied caller header would win.
 *
 * <p>A caller authenticated without an {@code Authorization} header (by a session cookie, say)
 * gets its {@code authorization} from the {@link UpstreamCredentialSource} bean, when there is one.
 *
 * <p>The result is passed to ReactiveGrpcDispatcher for every outbound gRPC call, enabling
 * backend services to perform their own authorization checks. To replace the propagation
 * logic, register a subclass bean marked {@code @Primary}.
 */
@Component
@ApiStatus(value = ApiStatus.Status.STABLE, since = "0.14.0")
public class GrpcMetadataPopulator {

    private static final Logger log = LoggerFactory.getLogger(GrpcMetadataPopulator.class);

    private static final Metadata.Key<String> PRINCIPAL_KEY =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Well-known distributed-tracing headers forwarded when
     * {@code shiguchi.client.forward-tracing-headers} is on (the default): W3C, B3,
     * and the common cloud/proxy request-id headers.
     */
    private static final List<String> TRACING_HEADERS = List.of(
            "traceparent",
            "tracestate",
            "b3",
            "x-b3-traceid",
            "x-b3-spanid",
            "x-b3-parentspanid",
            "x-b3-sampled",
            "x-b3-flags",
            "x-request-id",
            "x-amzn-trace-id",
            "x-cloud-trace-context");

    private final List<String> forwardHeaders;

    /** Null when no {@link UpstreamCredentialSource} bean exists. */
    private final UpstreamCredentialSource credentialSource;

    /**
     * Whether a metadata key is one of the tracing headers, which differ on every request and say
     * nothing about who is calling — the response cache leaves them out of its per-caller key.
     */
    public static boolean isTracingHeader(String key) {
        return TRACING_HEADERS.contains(key.toLowerCase(Locale.ROOT));
    }

    public GrpcMetadataPopulator(GatewayProperties properties) {
        this(properties, (UpstreamCredentialSource) null);
    }

    /**
     * The constructor Spring uses: as {@link #GrpcMetadataPopulator(GatewayProperties)}, plus the
     * {@link UpstreamCredentialSource} bean when one exists.
     *
     * @throws IllegalStateException when more than one source bean exists
     */
    @Autowired
    @ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
    public GrpcMetadataPopulator(GatewayProperties properties, ObjectProvider<UpstreamCredentialSource> sources) {
        this(properties, single(sources));
    }

    private static UpstreamCredentialSource single(ObjectProvider<UpstreamCredentialSource> sources) {
        List<UpstreamCredentialSource> all = sources.stream().toList();
        if (all.size() > 1) {
            throw new IllegalStateException("At most one UpstreamCredentialSource bean may exist, found "
                    + all.stream().map(s -> s.getClass().getName()).toList());
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private GrpcMetadataPopulator(GatewayProperties properties, UpstreamCredentialSource credentialSource) {
        this.credentialSource = credentialSource;
        var headers = new java.util.LinkedHashSet<String>();
        if (properties.getClient().isForwardTracingHeaders()) {
            headers.addAll(TRACING_HEADERS);
        }
        for (String header : properties.getClient().getForwardHeaders()) {
            if (header == null || header.isBlank()) continue;
            String name = header.trim().toLowerCase(Locale.ROOT);
            if (isGatewaySet(name)) {
                log.warn(
                        "shiguchi.client.forward-headers lists '{}', which the gateway sets itself; the caller's"
                                + " value is not forwarded",
                        name);
            } else {
                headers.add(name);
            }
        }
        // The headers version routing reads (services[].versions.sticky-header / preview-header)
        // must reach the call's metadata for the load balancer to see them. Names the gateway sets
        // itself are never copied from the request: a caller's own x-principal header would
        // otherwise travel next to the authenticated one.
        for (var service : properties.getServices()) {
            var versions = service.getVersions();
            if (versions == null) continue;
            for (String header : new String[] {versions.getStickyHeader(), versions.getPreviewHeader()}) {
                if (header == null || header.isBlank()) continue;
                String name = header.trim().toLowerCase(Locale.ROOT);
                if (!isGatewaySet(name)) headers.add(name);
            }
        }
        this.forwardHeaders = List.copyOf(headers);
    }

    private static boolean isGatewaySet(String name) {
        return name.equals("x-principal") || name.equals("authorization");
    }

    /** Builds metadata from an HTTP request â€” used by ShiguchiFilter (REST path). */
    public Mono<Metadata> build(ServerHttpRequest request) {
        if (credentialSource == null) {
            return ReactiveSecurityContextHolder.getContext()
                    .map(SecurityContext::getAuthentication)
                    .map(auth -> auth != null ? auth.getName() : "anonymous")
                    .defaultIfEmpty("anonymous")
                    .map(principal -> metadata(request, principal, null));
        }
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .flatMap(auth -> {
                    String authHeader = request.getHeaders().getFirst("Authorization");
                    if ((authHeader != null && !authHeader.isBlank()) || !isCaller(auth)) {
                        return Mono.just(metadata(request, auth.getName(), null));
                    }
                    return credentialSource
                            .authorization(request, auth)
                            .map(credential -> metadata(request, auth.getName(), credential))
                            .switchIfEmpty(Mono.fromSupplier(() -> metadata(request, auth.getName(), null)));
                })
                .switchIfEmpty(Mono.fromSupplier(() -> metadata(request, "anonymous", null)));
    }

    /** Authenticated and not Spring's anonymous token: someone the source may speak for. */
    private static boolean isCaller(Authentication auth) {
        return auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    /** {@code credential} is sent only when the request carries no Authorization header. */
    private Metadata metadata(ServerHttpRequest request, String principal, String credential) {
        Metadata metadata = new Metadata();
        metadata.put(PRINCIPAL_KEY, principal);
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            metadata.put(AUTHORIZATION_KEY, authHeader);
        } else if (credential != null && !credential.isBlank()) {
            metadata.put(AUTHORIZATION_KEY, credential);
        }
        for (String header : forwardHeaders) {
            String value = request.getHeaders().getFirst(header);
            if (value != null && !value.isBlank()) {
                metadata.put(Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER), value);
            }
        }
        return metadata;
    }
}
