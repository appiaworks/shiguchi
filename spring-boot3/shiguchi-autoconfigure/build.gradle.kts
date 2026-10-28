dependencies {
    api(platform(libs.spring.boot.bom))
    api(platform(libs.spring.cloud.gateway.dep))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    api(project(":shiguchi-grpc-proxy"))
    api(libs.spring.cloud.starter.gateway) {
        exclude("org.springframework.boot", "spring-boot-starter-jackson")
    }
    api(libs.spring.boot.autoconfigure)
    api(libs.spring.boot.starter.security)
    // OAuth2 resource server is only exercised in AUTHENTICATED mode / ui.require-auth=true.
    // implementation (not api) so permit-all-only consumers don't get it on their compile path;
    // the auto-config still activates at runtime when JWT properties are present.
    api(libs.spring.boot.starter.actuator)
    api(libs.grpc.otel)
    implementation("io.opentelemetry:opentelemetry-api")
    runtimeOnly(libs.micrometer.otel)
    runtimeOnly(libs.otel.exporter)
    runtimeOnly(libs.micrometer.prom)

    compileOnly(project(":shiguchi-graphql"))
    compileOnly(project(":shiguchi-openapi"))
    compileOnly(project(":shiguchi-ui"))


    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
