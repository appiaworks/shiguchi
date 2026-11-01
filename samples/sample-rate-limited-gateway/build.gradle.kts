plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"


val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    // The starter, not shiguchi-openapi directly: springdoc is Boot-line specific, so the
    // shared module declares it compileOnly and the per-line starter brings the matching version.
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot3-starter:$gatewayVersion")

    // The `redis` profile switches shiguchi.rate-limit.backend to REDIS, which needs Boot's
    // ReactiveStringRedisTemplate. The starter is harmless under the default (MEMORY) profile:
    // Boot autoconfigures a lazy connection factory, but nothing talks to Redis unless the
    // limiter does.
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    // Prometheus endpoint so the gateway.ratelimit.requests counter is visible at
    // /actuator/prometheus.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // The catalog backend runs in-process, built from a descriptor at startup rather than from
    // generated stubs, so the sample is one module with no codegen step.
    implementation("io.grpc:grpc-services")
    implementation("io.grpc:grpc-netty")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag =
        if (registry.isNotEmpty()) "$registry/shiguchi/sample-rate-limited-gateway:${project.version}"
        else "shiguchi/sample-rate-limited-gateway:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
