dependencies {
    implementation(platform(libs.spring.boot.bom))
    // SCG is compileOnly so the JARs are not forced onto consumers' classpaths.
    // Gateway autoconfigure modules declare it explicitly with the right SB version.
    compileOnly(platform(libs.spring.cloud.gateway.dep))
    api(project(":shiguchi-core"))
    compileOnly(libs.spring.cloud.starter.gateway) {
        exclude("org.springframework.boot", "spring-boot-starter-jackson")
    }
    api(libs.spring.boot.starter.security)
    // GatewaySecurityConfig wires a JWT resource server when one is configured (ReactiveJwtDecoder).
    api(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.grpc.otel)
    // MultiChildLoadBalancer, for the version-routing policy; already on the runtime path through grpc-services.
    implementation(libs.grpc.util)
    // inprocess:<name> targets (the hosted push service, tests) dial a server in this JVM.
    implementation(libs.grpc.inprocess)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation(libs.classgraph)
    implementation("io.micrometer:micrometer-core")
    // REST_DOWNSTREAM documents may be YAML; SnakeYAML ships with spring-boot-starter at runtime.
    compileOnly("org.yaml:snakeyaml")
    // The REDIS rate-limit backend; optional, the MEMORY backend needs nothing.
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis-reactive")


    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    // Arrives through the starter anyway; declared because a test imports it directly.
    testImplementation(libs.awaitility)
    testImplementation(platform(libs.spring.cloud.gateway.dep))
    testImplementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux") {
        exclude("org.springframework.boot", "spring-boot-starter-jackson")
    }
    testImplementation("ch.qos.logback:logback-classic")
    testImplementation("org.yaml:snakeyaml")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    // Runs a real Redis for RedisRateLimiterScriptTest: the token-bucket Lua script is the one
    // piece of the limiter that executes inside Redis, so a mocked template cannot cover it.
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}