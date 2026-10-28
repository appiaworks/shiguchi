package dev.appiaworks.shiguchi.sample.bff.issuer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A tiny OpenID Connect issuer for the browser sign-in sample: Spring Authorization Server with one in-memory
 * user (alice / password) and one confidential client, both declared in {@code application.yml}.
 * Not for production: the signing key is generated at startup, so every restart invalidates all
 * tokens, and users and clients live in memory.
 */
@SpringBootApplication
public class SampleBffIssuerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleBffIssuerApplication.class, args);
    }
}
