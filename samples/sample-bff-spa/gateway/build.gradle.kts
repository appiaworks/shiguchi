plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

version = "0.13.0"

val gatewayVersion = providers.gradleProperty("gatewayVersion").getOrElse("0.13.0")

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")

    // Browser sign-in with a session cookie, and push over the same cookie.
    implementation("io.github.appiaworks:shiguchi-bff-spring-boot3-starter:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-cursus-spring-boot3-starter:$gatewayVersion")

    // The gateway validates bearer tokens (and the session's access token) against the issuer.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("io.grpc:grpc-services")
    implementation("io.grpc:grpc-netty")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

// The single-page app is built by `npm run build` in ../web, which writes straight into
// src/main/resources/static (gitignored). Gradle never runs npm: build the page first, then
// `bootRun`. This task only reports whether the page is there.
tasks.register("checkWebBuilt") {
    group = "verification"
    description = "Fails unless the web part has been built into src/main/resources/static."
    val index = layout.projectDirectory.file("src/main/resources/static/index.html")
    doLast {
        check(index.asFile.exists()) { "Run `npm install && npm run build` in samples/sample-bff-spa/web first." }
    }
}
