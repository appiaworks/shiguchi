import org.gradle.api.tasks.SourceSetContainer

// Tests reuse the bff StubIssuer (an in-process OIDC issuer) from -bff's test sources.
evaluationDependsOn(":shiguchi-bff")

// Resolved outside the dependencies block: there `project(...)` is a dependency factory, not a Project.
val bffTestOutput = project(":shiguchi-bff").extensions.getByType<SourceSetContainer>()["test"].output

dependencies {
    api(project(":shiguchi-spring-boot4-autoconfigure"))
    api(project(":shiguchi-bff"))

    testImplementation(bffTestOutput)
    // Push with the session cookie: the push module runs in the same gateway.
    testImplementation(project(":shiguchi-cursus"))
    // The request-hardening end-to-end test posts GraphQL queries.
    testImplementation(project(":shiguchi-graphql-spring-boot4-starter"))
    // Token refresh across instances: two gateways over one embedded Redis, and over one embedded PostgreSQL.
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("org.postgresql:postgresql")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    // The stream-binding test parses GraphQL WebSocket frames with Jackson 2, which the modules do not export.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Reactor Netty on NIO, not native epoll. On the Linux CI runners, 7 of 8 runs had a test
// hang for 30 s: a request reached the server's socket and was not read until the
// test gave up and the client closed the connection, with every event loop idle (seen on Netty
// 4.1.135 and 4.2.17, Java 25; access log and thread dump in CI runs 40614148681 and
// 40614151438). With NIO the same tests passed twice in a row (40615153692, 40615156936). This
// module runs dozens of servers and event loop groups in one JVM; the switch is for its tests
// only.
tasks.withType<Test>().configureEach {
    systemProperty("reactor.netty.native", "false")
}
