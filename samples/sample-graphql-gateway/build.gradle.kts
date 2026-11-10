plugins {
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

// DGS 12.1 needs json-path 3; the Boot 4.1 BOM holds it at 2.10 (docs/operations/troubleshooting.md).
extra["json-path.version"] = "3.0.0"

val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    // GraphQL gateway — REST + GraphQL, no gRPC-proxy, no gRPC-UI
    implementation("io.github.appiaworks:shiguchi-spring-boot4-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-graphql:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-openapi:$gatewayVersion")
}

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag = if (registry.isNotEmpty()) "$registry/shiguchi/sample-graphql-gateway:${project.version}"
              else "shiguchi/sample-graphql-gateway:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
