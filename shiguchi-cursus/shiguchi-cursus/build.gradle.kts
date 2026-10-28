dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shiguchi-core"))
    // GatewayAuthorizer, the SSE keepalive and the security chain's principal all live in -rest.
    api(project(":shiguchi-rest"))
    // The SPIs speak Flux/Mono; WebFlux itself arrives through the gateway starter, as for -rest.
    api(libs.reactor.core)
    compileOnly(libs.spring.boot.starter.webflux)
    // The gateway-push actuator endpoint; present only when Actuator is (the gateway starters bring it).
    compileOnly(libs.spring.boot.starter.actuator)
    // The REDIS broker and store; optional, the MEMORY engine needs nothing.
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    // The POSTGRES broker and store; optional too: Spring's DatabaseClient over the Postgres R2DBC driver.
    compileOnly("org.springframework.boot:spring-boot-starter-data-r2dbc")
    compileOnly("org.postgresql:r2dbc-postgresql")
    // The JDBC broker and store; optional too: Spring's JdbcTemplate over any blocking DataSource.
    compileOnly("org.springframework:spring-jdbc")
    // The KAFKA source; optional: the consumer client only, no Spring Kafka.
    compileOnly("org.apache.kafka:kafka-clients")
    // The PushService gRPC service is hosted over an in-process channel the gateway dials like any backend.
    implementation(libs.grpc.inprocess)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("io.micrometer:micrometer-core")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    // A real Redis for the REDIS engine tests: Pub/Sub and Streams cannot be faked meaningfully.
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    testImplementation("org.postgresql:r2dbc-postgresql")
    // The JDBC engine tests: H2 in its native mode, and pgjdbc against the embedded Postgres below.
    testImplementation("org.springframework:spring-jdbc")
    testImplementation("com.h2database:h2")
    testImplementation("org.postgresql:postgresql")
    // A real Postgres for the POSTGRES engine tests: LISTEN/NOTIFY and the id sequence cannot be faked.
    testImplementation("org.apache.kafka:kafka-clients")
    // A real Kafka for the KAFKA source tests: spring-kafka-test's embedded KRaft broker.
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    testRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// PushClientProtocolFixtureTest reads the TypeScript client's frame fixture; rerun when it changes.
tasks.withType<Test>().configureEach {
    inputs.file("../../shiguchi-cursus-client-js/test/fixtures/frames.json").withPropertyName("pushClientFrames")
}
