dependencies {
    api(project(":shiguchi-spring-boot3-autoconfigure"))
    api(project(":shiguchi-avro"))

    // End-to-end test: the gateway with the GraphQL surface in front of a reactor-netty stub
    // that answers avro/binary.
    testImplementation(project(":shiguchi-graphql-spring-boot3-starter"))
    // The REST-surface test also checks /v3/api-docs advertises avro/binary.
    testImplementation(project(":shiguchi-openapi-spring-boot3-starter"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
