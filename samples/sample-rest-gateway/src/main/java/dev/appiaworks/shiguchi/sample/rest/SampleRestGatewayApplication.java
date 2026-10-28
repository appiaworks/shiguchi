package dev.appiaworks.shiguchi.sample.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demonstrates the REST gateway feature: gRPC services exposed as HTTP/JSON endpoints.
 *
 * Downstream gRPC services are discovered via gRPC reflection (DYNAMIC mode) or
 * a pre-built .pb file (PB_FILE mode). HTTP bindings come from google.api.http annotations
 * on the proto methods.
 *
 * Try: curl http://localhost:8080/v1/my-service/some-method
 */
@SpringBootApplication
public class SampleRestGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleRestGatewayApplication.class, args);
    }
}
