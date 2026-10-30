plugins {
    id("com.github.node-gradle.node")
}

node {
    version.set("22.13.1")
    npmVersion.set("10.9.2")
    // Download Node automatically — no local Node install required
    download.set(true)
    nodeProjectDir.set(file("${rootProject.projectDir}/shiguchi-ui-frontend"))
}

// ── Frontend build tasks ──────────────────────────────────────────────────

val npmInstall by tasks.named<com.github.gradle.node.npm.task.NpmTask>("npmInstall") {
    // Re-run only when package-lock.json changes (files() tolerates absence on first run)
    inputs.files("${rootProject.projectDir}/shiguchi-ui-frontend/package-lock.json")
    outputs.dir("${rootProject.projectDir}/shiguchi-ui-frontend/node_modules")
}

val frontendOutputDir = layout.buildDirectory.dir("generated/grpc-ui-static")

val npmBuild by tasks.register<com.github.gradle.node.npm.task.NpmTask>("npmBuild") {
    dependsOn(npmInstall)
    args.set(listOf("run", "build"))
    inputs.dir("${rootProject.projectDir}/shiguchi-ui-frontend/src")
    // The console compiles the push client from source (see vite.config.ts).
    inputs.dir("${rootProject.projectDir}/shiguchi-cursus-client-js/src")
    inputs.file("${rootProject.projectDir}/shiguchi-ui-frontend/index.html")
    inputs.file("${rootProject.projectDir}/shiguchi-ui-frontend/vite.config.ts")
    outputs.dir(frontendOutputDir)
}

// Add generated frontend assets as a resource directory so they end up in the jar
sourceSets.named("main") {
    resources.srcDir(frontendOutputDir)
}

// Wire the frontend build into the Java resource processing and sources jar
tasks.named("processResources") {
    dependsOn(npmBuild)
}

tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(npmBuild)
}

// ── Java dependencies ──────────────────────────────────────────────────────

dependencies {
    api(platform(libs.spring.boot.bom))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    api(libs.reactor.core)
    api(libs.spring.boot.starter.security)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    // Each web stack is compileOnly: the WebFlux adapter and the servlet adapter ship in the same jar,
    // and the starter for the stack in use supplies the real thing at runtime.
    compileOnly("org.springframework:spring-webflux")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
