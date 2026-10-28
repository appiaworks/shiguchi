plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.10.0"
    java
}

group = "dev.appiaworks.shiguchi.sample"

version = "0.13.0"

val gatewayVersion = providers.gradleProperty("gatewayVersion").getOrElse("0.13.0")

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot3-starter:$gatewayVersion")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Unlike the other samples, this one compiles real protos: STATIC mode reads its schema out
    // of the generated *Grpc classes, so they have to exist.
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("com.google.api.grpc:proto-google-common-protos:2.77.0")
    implementation("io.grpc:grpc-protobuf:1.84.0")
    implementation("io.grpc:grpc-stub:1.84.0")
    implementation("io.grpc:grpc-netty:1.84.0")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53") // javax.annotation.Generated on JDK 9+

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.84.0" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { create("grpc") }

            // The same protoc run also emits a FileDescriptorSet, which is what the `pb` profile
            // (PB_FILE mode) reads instead of the compiled classes.
            //
            // --include_imports is not optional here: without it the set omits
            // google/api/annotations.proto, the gateway cannot resolve the google.api.http
            // options, and every method registers with no REST route at all — a gateway that
            // starts cleanly and 404s everything.
            task.generateDescriptorSet = true
            task.descriptorSetOptions.includeImports = true
            // Keeps the .proto comments in the descriptor, which is what lets the gateway use
            // them as OpenAPI and GraphQL descriptions in PB_FILE mode. Reflection never sends
            // source info and compiled stubs do not retain it, so this is the only mode where
            // the documentation you already wrote can reach the generated surfaces.
            task.descriptorSetOptions.includeSourceInfo = true
            task.descriptorSetOptions.path = "${layout.buildDirectory.get()}/descriptors/catalog.pb"
        }
    }
}

tasks.withType<Test> { useJUnitPlatform() }
