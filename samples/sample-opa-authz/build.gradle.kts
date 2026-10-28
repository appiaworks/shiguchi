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

    // OPA has no Java client SDK worth depending on — its data API is a plain HTTP POST of a
    // JSON document. WebClient is already on the classpath via the gateway, so the whole
    // integration is one bean and no new dependency.
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // JWT resource server — the caller identity that goes into the OPA input document.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag = if (registry.isNotEmpty()) "$registry/shiguchi/sample-opa-authz:${project.version}"
              else "shiguchi/sample-opa-authz:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
