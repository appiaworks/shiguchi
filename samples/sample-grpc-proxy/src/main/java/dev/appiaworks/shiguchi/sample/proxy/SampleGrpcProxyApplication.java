package dev.appiaworks.shiguchi.sample.proxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Demonstrates the gRPC proxy feature: a single gRPC endpoint that forwards calls
 * to multiple downstream gRPC services.
 *
 * Clients connect to this proxy as if it were a regular gRPC server. The proxy
 * discovers downstream services via reflection and routes by service FQN.
 *
 * Try: grpcurl -plaintext localhost:9191 list
 */
@SpringBootApplication
public class SampleGrpcProxyApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleGrpcProxyApplication.class, args);
    }
}
