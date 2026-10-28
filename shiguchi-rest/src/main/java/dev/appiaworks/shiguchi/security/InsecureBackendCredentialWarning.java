package dev.appiaworks.shiguchi.security;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.SecurityConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warns when the gateway would put a caller's bearer token on the wire in plaintext.
 *
 * <p>The gateway forwards the inbound {@code Authorization} header to backends, so they can do
 * their own authorization. A backend reached over plaintext — {@code insecure: true}, which is the
 * **default** for a service definition — therefore receives that token unencrypted, readable by
 * anything on the path. Inside a service mesh with its own mTLS that is fine and deliberate;
 * across anything less trusted it is a credential leak that no error would ever reveal.
 *
 * <p>So it is said out loud, once, at startup, naming the services concerned. The gateway does not
 * refuse to start: plaintext to a sidecar is a legitimate and common deployment, and failing it
 * would break those users for no benefit.
 *
 * <p>Nothing is logged when authentication is off everywhere — with no identity provider there is
 * no token to leak, and the {@code PERMIT_ALL} warning already covers that posture.
 */
@Component
public class InsecureBackendCredentialWarning {

    private static final Logger log = LoggerFactory.getLogger(InsecureBackendCredentialWarning.class);

    private final GatewayProperties properties;

    public InsecureBackendCredentialWarning(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * After startup, so it lands near the other posture messages rather than in the middle of
     * bean creation.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnOnPlaintextCredentialForwarding() {
        List<String> plaintext = plaintextBackends();
        if (plaintext.isEmpty() || !authenticationConfigured()) return;

        log.warn("""
                gRPC gateway security: bearer tokens are forwarded to {} backend(s) over PLAINTEXT \
                ({}). The gateway copies the caller's Authorization header onto every outbound \
                call, so those tokens are readable by anything on the network path. This is fine \
                only when the hop is already protected — a service mesh with its own mTLS, or \
                localhost. Otherwise set insecure: false (or a tls block) on those services, or \
                drop the header with your own GrpcMetadataPopulator bean. See SECURITY.md.""", plaintext.size(), String.join(", ", plaintext));
    }

    /** gRPC backends this gateway will talk to without TLS. */
    private List<String> plaintextBackends() {
        return properties.getServices().stream()
                .filter(service -> service.getMode() != GatewayMode.REST_DOWNSTREAM)
                // A tls block wins over the insecure flag, so a service that has one is encrypted.
                .filter(service -> service.getTls() == null && service.isInsecure())
                .map(ServiceDefinition::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    /**
     * Whether any surface authenticates callers. Without that there is no token to forward, and
     * the open-posture warning is the one worth reading.
     */
    private boolean authenticationConfigured() {
        SecurityConfig security = properties.getSecurity();
        boolean modeAuth = security.getMode() == SecurityConfig.Mode.AUTHENTICATED;
        SecurityConfig.SurfacesConfig surfaces = security.getSurfaces();
        return surfaces.getRest().effective(modeAuth)
                || surfaces.getGraphql().effective(modeAuth)
                || surfaces.getUi().effective(modeAuth)
                || surfaces.getOpenapi().effective(modeAuth)
                || properties.getUi().isRequireAuth();
    }
}
