dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":shiguchi-ui-spring-boot3-autoconfigure"))
    api(project(":shiguchi-ui"))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    api(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
