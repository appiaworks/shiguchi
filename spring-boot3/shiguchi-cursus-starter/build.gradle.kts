dependencies {
    api(project(":shiguchi-spring-boot3-autoconfigure"))
    api(project(":shiguchi-cursus"))

    // End-to-end test: the gateway with push on, SSE subscribers and HTTP publishers over a port.
    // The admin end-to-end test reads the console's config, which advertises the push endpoint.
    testImplementation(project(":shiguchi-ui-spring-boot3-starter"))
    // The GraphQL subscription fields end to end: the push namespaces under DGS over graphql-transport-ws.
    testImplementation(project(":shiguchi-graphql-spring-boot3-starter"))
    // The cursus command line, run in-process by PushCliEndToEndTest.
    testImplementation(project(":shiguchi-cursus-cli"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    // The WebSocket test parses frames with Jackson 2, which the push module does not export.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    // Two gateway instances over one embedded Redis: the point of the REDIS engine.
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    // Two gateway instances over one embedded Postgres: the POSTGRES engine.
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    testImplementation("org.postgresql:r2dbc-postgresql")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    // Two gateway instances over one in-memory H2: the JDBC engine, no server and no Docker.
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
