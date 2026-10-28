dependencies {
    api(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-validation"))
    api(libs.spring.boot.autoconfigure)

    compileOnly(project(":shiguchi-rest"))
    compileOnly(project(":shiguchi-grpc-proxy"))


    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
