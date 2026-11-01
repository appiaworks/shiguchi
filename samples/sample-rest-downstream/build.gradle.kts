plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"


val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    // REST + OpenAPI + GraphQL surfaces. The starters bring springdoc for the merged
    // /v3/api-docs and DGS for the schema derived from the backend's document.
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot3-starter:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-graphql-spring-boot3-starter:$gatewayVersion")

    // The REST backend this sample fronts runs in-process on :8081 (plain reactor-netty, which
    // the gateway already depends on), so the sample is one module and needs nothing external.
    implementation("io.projectreactor.netty:reactor-netty-http")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag =
        if (registry.isNotEmpty()) "$registry/shiguchi/sample-rest-downstream:${project.version}"
        else "shiguchi/sample-rest-downstream:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
