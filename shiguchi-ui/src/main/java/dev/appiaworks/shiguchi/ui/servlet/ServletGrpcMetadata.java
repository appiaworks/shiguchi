package dev.appiaworks.shiguchi.ui.servlet;

import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import io.grpc.Metadata;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Builds the gRPC call {@link Metadata} for a UI call in a servlet application, the counterpart
 * of {@link GrpcMetadataPopulator} for the reactive stack: {@code x-principal} (the authenticated
 * principal's name, or {@code anonymous} for no or an anonymous caller), the {@code Authorization} header when the request
 * carries one, and the headers named by {@code shiguchi.client.forward-headers} and friends (the
 * same list as the reactive populator, see {@link GrpcMetadataPopulator#forwardedHeaderNames}).
 * {@code x-principal} and {@code authorization} are never copied from a forwarded header.
 *
 * <p>The security context is thread-bound, so {@link #build} must run on the request thread,
 * before the call goes asynchronous.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.function.RouterFunction")
@ConditionalOnProperty(prefix = "shiguchi.ui", name = "enabled", havingValue = "true")
public class ServletGrpcMetadata {

    private static final Metadata.Key<String> PRINCIPAL_KEY =
            Metadata.Key.of("x-principal", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final List<String> forwardHeaders;

    public ServletGrpcMetadata(GatewayProperties properties) {
        this.forwardHeaders = GrpcMetadataPopulator.forwardedHeaderNames(properties);
    }

    /** The metadata for a call made on behalf of the current request and security context. */
    public Metadata build(HttpServletRequest request) {
        Metadata metadata = new Metadata();
        metadata.put(PRINCIPAL_KEY, principalName());
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            metadata.put(AUTHORIZATION_KEY, authHeader);
        }
        for (String header : forwardHeaders) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank()) {
                metadata.put(Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER), value);
            }
        }
        return metadata;
    }

    private static String principalName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // Servlet security names its anonymous caller "anonymousUser"; the reactive populator sends
        // "anonymous", and backends see one spelling whichever stack the UI runs on.
        if (auth == null || auth instanceof AnonymousAuthenticationToken || auth.getName() == null) {
            return "anonymous";
        }
        return auth.getName();
    }
}
