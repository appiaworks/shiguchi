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

    // Per-object authorization: the OpenFGA client used by the custom GatewayAuthorizer.
    // The official openfga/spring-boot-starter is deliberately NOT used — it is servlet-only
    // (@PreAuthorize/@fga) and this gateway is reactive WebFlux end to end. The raw SDK
    // returns CompletableFuture, which bridges to Mono without blocking.
    implementation("dev.openfga:openfga-sdk:0.10.1")

    // Decision cache. Caffeine is already on the classpath transitively via the gateway,
    // but declare it explicitly since this sample uses it directly.
    implementation("com.github.ben-manes.caffeine:caffeine:3.3.0")

    // JWT resource server — the caller identity the FGA check is performed for.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag = if (registry.isNotEmpty()) "$registry/shiguchi/sample-openfga-authz:${project.version}"
              else "shiguchi/sample-openfga-authz:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
