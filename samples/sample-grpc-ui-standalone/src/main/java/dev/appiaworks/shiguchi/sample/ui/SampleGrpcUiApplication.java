package dev.appiaworks.shiguchi.sample.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demonstrates the gRPC UI as a standalone tool — no HTTP/GraphQL routing involved.
 *
 * Useful as a developer-facing UI that sits alongside your services without needing
 * a full gateway deployment. Point it at any gRPC service with reflection enabled.
 *
 * Open: http://localhost:8080/grpc-ui
 */
@SpringBootApplication
public class SampleGrpcUiApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleGrpcUiApplication.class, args);
    }
}
