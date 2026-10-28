dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    // The only Avro dependency the gateway takes: schema parsing, generic records, binary codec.
    api(libs.avro)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    // The schema endpoint is a WebFlux controller; the runtime comes from the gateway starter.
    compileOnly(libs.spring.boot.starter.webflux)


    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    // Registry-client tests run WebClient against a reactor-netty stub.
    testImplementation(libs.spring.boot.starter.webflux)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
