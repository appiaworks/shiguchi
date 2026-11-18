dependencies {
    api(platform(libs.spring.boot4.bom))
    api(platform(libs.spring.cloud.gateway4.dep))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    api(project(":shiguchi-grpc-proxy"))
    api(libs.spring.cloud.starter.gateway)
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

    // GatewayRefreshScheduler references these optional modules at compile time;
    // they are provided at runtime by the graphql/openapi/ui starters.
    compileOnly(project(":shiguchi-graphql"))
    compileOnly(project(":shiguchi-openapi"))
    compileOnly(project(":shiguchi-ui"))

    // GrpcPortRegistrationAutoConfiguration runs in a BACKEND app and only when its own Eureka
    // client is on the classpath, so the client is never a dependency of this library.
    compileOnly(platform(libs.spring.cloud.train.bom))
    compileOnly(libs.spring.cloud.eureka.client)
    testImplementation(platform(libs.spring.cloud.train.bom))
    testImplementation(libs.spring.cloud.eureka.client)
    // Same for the Consul publisher: the backend brings spring-cloud-consul-discovery.
    compileOnly(libs.spring.cloud.consul.discovery)
    testImplementation(libs.spring.cloud.consul.discovery)
    // Spring Boot's gRPC server support (Spring gRPC 1.1+ moved its auto-configuration into Boot 4.1);
    // optional in the backend app, never a dependency of this library.
    compileOnly(libs.spring.boot.grpc.server)
    testImplementation(libs.spring.boot.grpc.server)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
