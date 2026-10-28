dependencies {
    api(project(":shiguchi-spring-boot4-autoconfigure"))
    api(project(":shiguchi-graphql"))
    // Boot 4 line: DGS 12.x (Spring 7). shiguchi-graphql compiles against the shared
    // DGS API surface and gets the line-matching runtime from this starter.
    api(platform(libs.dgs.platform))
    api(libs.dgs.spring.graphql)
    api(libs.dgs.extended.scalars)
    api(libs.dgs.micrometer)

    // End-to-end test: the gateway with DGS in front of a reactor-netty stub REST backend.
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
