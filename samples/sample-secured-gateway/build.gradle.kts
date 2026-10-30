plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

version = "0.14.1"

val gatewayVersion = providers.gradleProperty("gatewayVersion").getOrElse("0.14.1")

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot3-starter:$gatewayVersion")

    // Inbound authentication: resource server validates the caller's bearer token.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("io.grpc:grpc-services")
    implementation("io.grpc:grpc-netty")

    // Only to generate throwaway certificates at startup, so no private key is committed to the
    // repository. A real deployment mounts its PEM files and needs none of this.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
