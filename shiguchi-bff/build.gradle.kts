import org.gradle.api.tasks.SourceSetContainer

// The bff credential source runs the UpstreamCredentialSourceContract from -rest's test sources
// (the repository has no test fixtures; abstract contract bases live with the tests).
evaluationDependsOn(":shiguchi-rest")

// Resolved outside the dependencies block: there `project(...)` is a dependency factory, not a Project.
val restTestOutput = project(":shiguchi-rest").extensions.getByType<SourceSetContainer>()["test"].output

dependencies {
    implementation(platform(libs.spring.boot.bom))
    // GatewaySecurityCustomizer, UpstreamCredentialSource and the request attributes live in -rest,
    // which also brings Spring Security and the JWT resource server the cookie principal shares.
    api(project(":shiguchi-rest"))
    api(libs.reactor.core)
    // The gateway.bff.refresh counter; -rest and push bring it the same way (implementation, so no new transitive).
    implementation("io.micrometer:micrometer-core")
    // WebFlux arrives through the gateway starter, as for -rest and push.
    compileOnly(libs.spring.boot.starter.webflux)
    // The gateway-bff-sessions actuator endpoint; present only when Actuator is (the gateway starters bring it).
    compileOnly(libs.spring.boot.starter.actuator)
    // The REDIS session store; optional, MEMORY needs nothing.
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    // The JDBC session store; optional too: Spring's JdbcTemplate over any blocking DataSource.
    compileOnly("org.springframework:spring-jdbc")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(restTestOutput)
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    // A real Redis for the REDIS store tests (Redis 5 on Windows, 6.2+ on Linux): scripts cannot be faked.
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    // The JDBC store tests: H2 in its native mode, and pgjdbc against the embedded Postgres below.
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("com.h2database:h2")
    testImplementation("org.postgresql:postgresql")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.actuator)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
