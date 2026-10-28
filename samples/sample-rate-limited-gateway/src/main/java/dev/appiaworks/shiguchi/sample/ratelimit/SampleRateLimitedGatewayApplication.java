package dev.appiaworks.shiguchi.sample.ratelimit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

/**
 * A REST gateway with inbound rate limiting in front of one gRPC service.
 *
 * <p>The limiter itself is all configuration ({@code shiguchi.rate-limit.*} in
 * {@code application.yml}); this class only starts the in-process catalog backend the gateway
 * fronts. Run with {@code --spring.profiles.active=redis} to share the buckets across gateway
 * instances through Redis instead of keeping them in memory.
 */
@SpringBootApplication
public class SampleRateLimitedGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleRateLimitedGatewayApplication.class, args);
    }

    /**
     * Runs the catalog backend for the lifetime of the app.
     *
     * <p>Component-scanned, so it is created before the autoconfigured gateway beans and is
     * already listening when the route loader reflects on it. In a real deployment the backend is
     * a separate process that is simply up; a backend that is not yet listening makes the gateway
     * defer the route load and report readiness DOWN until its retry succeeds.
     */
    @Component
    public static class Backend {

        private final CatalogServiceServer server;

        public Backend(@Value("${sample.backend-port:9090}") int port) {
            this.server = new CatalogServiceServer(port);
        }

        @PostConstruct
        public void start() throws Exception {
            server.start();
        }

        @PreDestroy
        public void stop() {
            server.stop();
        }
    }
}
