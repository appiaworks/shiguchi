plugins {
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    // gRPC UI only — browse any gRPC service via the embedded UI without routing
    implementation("io.github.appiaworks:shiguchi-ui-spring-boot4-starter:$gatewayVersion")
}

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag = if (registry.isNotEmpty()) "$registry/shiguchi/sample-grpc-ui-standalone:${project.version}"
              else "shiguchi/sample-grpc-ui-standalone:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
