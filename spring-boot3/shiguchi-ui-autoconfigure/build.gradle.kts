dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":shiguchi-ui"))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    api(libs.spring.boot.autoconfigure)


    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}