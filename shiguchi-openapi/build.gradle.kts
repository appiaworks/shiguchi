dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shiguchi-core"))
    // RestBodyCodec: non-JSON media types (Avro) advertised next to application/json.
    api(project(":shiguchi-rest"))
    // springdoc is Boot-line specific (2.x = Boot 3, 3.x = Boot 4). compileOnly here —
    // the per-line openapi starters supply the matching runtime version, so this shared
    // module's POM never drags one Boot line into the other.
    compileOnly(libs.springdoc.webflux)
    implementation("jakarta.annotation:jakarta.annotation-api")


    testImplementation(libs.spring.boot.starter.test)
    // Tests bind real swagger models; springdoc (Boot 3 line) supplies swagger-core at test runtime.
    testImplementation(libs.springdoc2.webflux)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}