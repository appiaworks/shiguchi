dependencies {
    implementation(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-core"))
    // RestBodyCodec: non-JSON media types (Avro) advertised next to application/json.
    api(project(":shiguchi-rest"))
    // compileOnly: the openapi starter supplies springdoc at runtime, so this shared module's
    // POM does not pin one.
    compileOnly(libs.springdoc.webflux)
    // DownstreamSpecMerger binds downstream specs through swagger-core's Json/Yaml mappers, which
    // are Jackson 2 ObjectMappers (swagger-core 2.2.x has no Jackson 3 API), so this one module
    // stays on Jackson 2 at that seam. springdoc supplies it at runtime; the version is the BOM's.
    compileOnly("com.fasterxml.jackson.core:jackson-databind")
    implementation("jakarta.annotation:jakarta.annotation-api")


    testImplementation(libs.spring.boot.starter.test)
    // Tests bind real swagger models; springdoc supplies swagger-core at test runtime.
    testImplementation(libs.springdoc.webflux)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}