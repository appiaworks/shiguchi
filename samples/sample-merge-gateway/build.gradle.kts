plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"


val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")

    // The two backends this sample merges run in-process, built from descriptors at startup
    // rather than from generated stubs — so the sample stays one module with no codegen step
    // and the merge behaviour is what you read, not what protoc emitted.
    implementation("io.grpc:grpc-services")
    implementation("io.grpc:grpc-netty")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }

tasks.bootBuildImage {
    val registry = project.findProperty("imageRegistry")?.toString() ?: ""
    val tag =
        if (registry.isNotEmpty()) "$registry/shiguchi/sample-merge-gateway:${project.version}"
        else "shiguchi/sample-merge-gateway:${project.version}"
    imageName.set(tag)
    builder.set("paketobuildpacks/builder-jammy-tiny")
    environment.set(mapOf("BP_JVM_VERSION" to "25"))
    publish.set(project.hasProperty("imageRegistry"))
}
