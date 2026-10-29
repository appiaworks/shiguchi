import org.gradle.api.tasks.SourceSetContainer

// Tests reuse the bff StubIssuer (an in-process OIDC issuer) from -bff's test sources.
evaluationDependsOn(":shiguchi-bff")

// Resolved outside the dependencies block: there `project(...)` is a dependency factory, not a Project.
val bffTestOutput = project(":shiguchi-bff").extensions.getByType<SourceSetContainer>()["test"].output

dependencies {
    api(project(":shiguchi-spring-boot3-autoconfigure"))
    api(project(":shiguchi-bff"))

    testImplementation(bffTestOutput)
    // Push with the session cookie: the push module runs in the same gateway.
    testImplementation(project(":shiguchi-cursus"))
    // The request-hardening end-to-end test posts GraphQL queries.
    testImplementation(project(":shiguchi-graphql-spring-boot3-starter"))
    // Token refresh across instances: two gateways over one embedded Redis, and over one embedded PostgreSQL.
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.postgresql:postgresql")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
