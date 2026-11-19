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
    // REST_DOWNSTREAM routes plus the GraphQL surface that carries the relationships. Nothing
    // here compiles a proto: every type comes from an OpenAPI document.
    implementation("io.github.appiaworks:shiguchi-graphql-spring-boot4-starter:$gatewayVersion")
    // The starter exposes the library at runtime only; BatchLoaderDefinition and
    // RelationshipDefinition (the bean path) are compiled against, so it is declared here.
    implementation("io.github.appiaworks:shiguchi-graphql:$gatewayVersion")

    // The REST backend this sample fronts runs in-process (plain reactor-netty, which the gateway
    // already depends on), so the sample is one module and needs nothing external.
    implementation("io.projectreactor.netty:reactor-netty-http")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
