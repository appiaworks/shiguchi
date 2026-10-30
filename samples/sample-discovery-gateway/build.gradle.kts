plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

version = "0.14.1"

val gatewayVersion = providers.gradleProperty("gatewayVersion").getOrElse("0.14.1")

dependencyManagement {
    // The Boot 3.5 train. spring-cloud-commons is all this sample needs (the DiscoveryClient
    // interface); a real deployment adds the Eureka/Consul/Kubernetes client from the same BOM,
    // which is why it is a BOM and not a pinned dependency.
    imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.0.3") }
}

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot3-starter:$gatewayVersion")

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
