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

// WORKAROUND, not a fix: one request per server connection in these tests. Reactor Netty
// reactor/reactor-netty#4361: a keep-alive request queued behind a still-live exchange and then
// answered at once leaves the connection with no read requested, so the next request sits unread
// until the client closes (CI runs 40614148681, 40614151438; none with keep-alive off). Fixed in
// 1.3.8 (#4347), not in 1.2.x, which Boot 3.5 uses (1.2.18). Applications keep the default.
tasks.withType<Test>().configureEach {
    systemProperty("server.netty.max-keep-alive-requests", "1")
}
