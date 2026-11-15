plugins {
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot4-starter:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")

    // Inbound authentication: the policy decides for a concrete caller, so callers must be known.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // The REST backend this sample fronts runs in-process (plain reactor-netty).
    implementation("io.projectreactor.netty:reactor-netty-http")

    // Parses the rules file. Already on the classpath through Spring Boot; named so it stays there.
    implementation("org.yaml:snakeyaml")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag =
        if (registry.isNotEmpty()) "$registry/shiguchi/sample-policy:${project.version}"
        else "shiguchi/sample-policy:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
