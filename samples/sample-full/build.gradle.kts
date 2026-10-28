plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"
version = "0.13.0"

val gatewayVersion = providers.gradleProperty("gatewayVersion").getOrElse("0.13.0")

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-starter:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-ui-spring-boot3-starter:$gatewayVersion")
    // Avro for REST callers: Accept/Content-Type avro/binary on every gRPC-backed route,
    // schemas at /avro/schemas/{proto full name}. Optional; remove the line and it is gone.
    implementation("io.github.appiaworks:shiguchi-avro-spring-boot3-starter:$gatewayVersion")
    // /actuator/prometheus, scraped by samples/observability (Prometheus + Grafana dashboard).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}

tasks.bootBuildImage {
    // Override with -PimageRegistry=ghcr.io/your-org to push to a registry
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag = if (registry.isNotEmpty()) "$registry/shiguchi/sample-full:${project.version}"
              else "shiguchi/sample-full:${project.version}"
    imageName.set(tag)
    // Base builder ships the JVM buildpacks needed for a standard executable jar.
    // (The tiny builder has no Java buildpack group and fails detection for JVM apps.)
    builder.set("paketobuildpacks/builder-jammy-base")
    environment.set(mapOf(
        "BP_JVM_VERSION" to "25",
        "BPL_JVM_THREAD_COUNT" to "50"
    ))
    publish.set(project.hasProperty("imageRegistry"))
}