package dev.appiaworks.shiguchi.config;

/** How backend load state affects Spring Boot's readiness. */
public enum ReadinessPolicy {
    ALL_LOADED,
    ANY_LOADED,
    IGNORE
}
