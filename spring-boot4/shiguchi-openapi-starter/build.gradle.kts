dependencies {
    api(project(":shiguchi-spring-boot4-autoconfigure"))
    api(project(":shiguchi-openapi"))
    // Boot 4 line: springdoc 3.x (shiguchi-openapi compiles against the shared API
    // surface and gets the line-matching runtime from this starter).
    api(libs.springdoc.webflux)

    // End-to-end test: the whole gateway (SCG + springdoc) in front of a reactor-netty stub.
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
