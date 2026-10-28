package dev.appiaworks.shiguchi.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample Spring Boot application demonstrating Shiguchi.
 *
 * Configure downstream gRPC services in application.yml under shiguchi.services[].
 * The gateway will auto-discover routes via gRPC server reflection (DYNAMIC mode)
 * or from a pre-built .pb descriptor file (PB_FILE mode).
 */
@SpringBootApplication
public class SampleGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleGatewayApplication.class, args);
    }
}