plugins {
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

// DGS 12.1 needs json-path 3; the Boot 4.1 BOM holds it at 2.10 (docs/operations/troubleshooting.md).
extra["json-path.version"] = "3.0.0"

val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencyManagement {
    // The Boot 4 train (2025.1, matching Spring Cloud Gateway 5.0). spring-cloud-commons is all this sample needs (the DiscoveryClient
    // interface); a real deployment adds the Eureka/Consul/Kubernetes client from the same BOM,
    // which is why it is a BOM and not a pinned dependency.
    imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.1") }
}

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot4-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot4-starter:$gatewayVersion")
    // The browser surfaces the UI smoke test (scripts/ui-smoke) loads: gRPC console and GraphiQL.
    implementation("io.github.appiaworks:shiguchi-ui-spring-boot4-starter:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-graphql-spring-boot4-starter:$gatewayVersion")

    // The DiscoveryClient interface the gateway's discovery:/// name resolver asks for. Swap for
    // spring-cloud-starter-netflix-eureka-client / -consul-discovery / -kubernetes-client to talk
    // to a real registry — the gateway configuration does not change.
    implementation("org.springframework.cloud:spring-cloud-commons")

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // The catalog instances run in-process, built from a descriptor at startup rather than from
    // generated stubs, so the sample is one module with no codegen step.
    implementation("io.grpc:grpc-services")
    implementation("io.grpc:grpc-netty")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag =
        if (registry.isNotEmpty()) "$registry/shiguchi/sample-discovery-gateway:${project.version}"
        else "shiguchi/sample-discovery-gateway:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
