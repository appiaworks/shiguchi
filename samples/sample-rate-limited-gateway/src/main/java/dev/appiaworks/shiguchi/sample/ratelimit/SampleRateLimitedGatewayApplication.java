package dev.appiaworks.shiguchi.sample.ratelimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A REST gateway with inbound rate limiting in front of one gRPC service.
 *
 * <p>The limiter itself is all configuration ({@code shiguchi.rate-limit.*} in
 * {@code application.yml}); the in-process catalog backend the gateway fronts is the
 * {@code CatalogServiceServer} bean, served by Spring gRPC. Run with {@code --spring.profiles.active=redis} to share the buckets across gateway
 * instances through Redis instead of keeping them in memory.
 */
@SpringBootApplication
public class SampleRateLimitedGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleRateLimitedGatewayApplication.class, args);
    }
}
