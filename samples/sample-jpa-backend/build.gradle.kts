plugins {
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.10.0"
    java
}

group = "dev.appiaworks.shiguchi.sample"

// DGS 12.1 needs json-path 3; the Boot 4.1 BOM holds it at 2.10 (docs/operations/troubleshooting.md).
extra["json-path.version"] = "3.0.0"


val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot4-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot4-starter:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-graphql-spring-boot4-starter:$gatewayVersion")

    // The point of this sample: sort_fields, read_mask and page_request turned into a JPA query.
    // The -jpa artifact brings shiguchi-backend-support (the Spec classes) with it.
    implementation("io.github.appiaworks:shiguchi-backend-support-jpa:$gatewayVersion")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")

    // Compiles real protos: STATIC mode reads the schema from the generated *Grpc classes, and the
    // service is implemented against them.
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("com.google.api.grpc:proto-google-common-protos:2.79.0")
    implementation("io.grpc:grpc-protobuf:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")
    implementation("io.grpc:grpc-netty:1.85.1")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // javax.annotation.Generated on JDK 9+

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.85.1" }
    }
    generateProtoTasks {
        all().forEach { task -> task.plugins { maybeCreate("grpc") } }
    }
}

tasks.withType<Test> { useJUnitPlatform() }
