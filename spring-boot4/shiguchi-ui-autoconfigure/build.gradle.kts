dependencies {
    api(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-ui"))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    api(libs.spring.boot.autoconfigure)


    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
