package dev.appiaworks.shiguchi.sample.secured;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

/**
 * A gateway secured on both sides: callers authenticate to it with a JWT, and it authenticates to
 * its backend with a client certificate.
 *
 * <p>Those are two different problems that a deployment has to solve together, which is why they
 * are one sample:
 *
 * <ul>
 *   <li><b>Inbound</b> — {@code security.mode: AUTHENTICATED} makes every surface require a valid
 *       bearer token, validated against a JWT decoder.
 *   <li><b>Outbound</b> — {@code services[].tls} gives the gateway a CA to verify the backend and
 *       a client certificate to present, so the hop that carries the forwarded {@code
 *       Authorization} header is encrypted and mutually authenticated.
 * </ul>
 *
 * <p>Getting only the first right is the common mistake: the gateway checks the caller's token
 * carefully and then forwards it to the backend in plaintext. The gateway warns about exactly
 * that at startup when a surface authenticates and a service has no {@code tls} block.
 */
@SpringBootApplication
public class SampleSecuredGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSecuredGatewayApplication.class, args);
    }

    /**
     * Generates the sample's throwaway certificates and publishes their paths as properties
     * <em>before</em> configuration binding, so {@code shiguchi.services[0].tls.*} can point
     * at files that did not exist when the JVM started.
     *
     * <p>Only needed because the certificates are generated rather than shipped — a real
     * deployment writes the paths in {@code application.yml} and this class does not exist. It is
     * registered in {@code META-INF/spring.factories}; an {@code @Bean} would run far too late.
     */
    public static class CertificateEnvironment implements EnvironmentPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(CertificateEnvironment.class);

        /** Shared with the backend component, which needs the same files. */
        static volatile DevCertificates certificates;

        @Override
        public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
            try {
                DevCertificates certs = DevCertificates.generate();
                certificates = certs;
                Map<String, Object> props = new HashMap<>();
                props.put("sample.certs.ca", certs.caCert().toString());
                props.put("sample.certs.client-cert", certs.clientCert().toString());
                props.put("sample.certs.client-key", certs.clientKey().toString());
                environment.getPropertySources().addFirst(new MapPropertySource("sample-dev-certificates", props));
                log.info("generated throwaway dev certificates in {}", certs.dir());
            } catch (Exception e) {
                throw new IllegalStateException("could not generate the sample's dev certificates", e);
            }
        }
    }

    /** Runs the TLS backend for the lifetime of the app. */
    @Component
    public static class Backend {

        private final SecuredCatalogBackend backend;

        public Backend(
                @org.springframework.beans.factory.annotation.Value("${sample.backend-port:9443}") int port) {
            this.backend = new SecuredCatalogBackend(port, CertificateEnvironment.certificates);
        }

        @PostConstruct
        public void start() throws Exception {
            backend.start();
        }

        @PreDestroy
        public void stop() {
            backend.stop();
        }
    }
}
