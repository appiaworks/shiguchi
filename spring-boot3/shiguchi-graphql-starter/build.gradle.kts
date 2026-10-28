dependencies {
    api(project(":shiguchi-spring-boot3-autoconfigure"))
    api(project(":shiguchi-graphql"))
    // Boot 3 line: DGS 10.x (Spring 6). shiguchi-graphql compiles against the shared
    // DGS API surface and gets the line-matching runtime from this starter.
    api(platform(libs.dgs10.platform))
    api(libs.dgs.spring.graphql)
    api(libs.dgs.extended.scalars)
    api(libs.dgs.micrometer)

    // End-to-end test: the gateway with DGS in front of a reactor-netty stub REST backend.
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
