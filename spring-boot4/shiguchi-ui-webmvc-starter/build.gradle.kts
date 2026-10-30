dependencies {
    api(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-ui-spring-boot4-autoconfigure"))
    api(project(":shiguchi-ui"))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    // Boot 4 renamed the servlet web starter; spring-boot-starter-web is its deprecated alias.
    api(libs.spring.boot.starter.webmvc)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
