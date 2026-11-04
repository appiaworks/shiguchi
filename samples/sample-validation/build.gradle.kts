plugins {
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.10.0"
    java
}

group = "dev.appiaworks.shiguchi.sample"


val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot4-autoconfigure:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-core:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-rest:$gatewayVersion")
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot4-starter:$gatewayVersion")

    // Brings protovalidate and the gateway's validation hook. It also puts
    // buf/validate/validate.proto on the proto path, which is what this sample's proto imports.
    implementation("io.github.appiaworks:shiguchi-validation-spring-boot4-starter:$gatewayVersion")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("com.google.api.grpc:proto-google-common-protos:2.79.0")
    implementation("io.grpc:grpc-protobuf:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")
    implementation("io.grpc:grpc-services:1.85.1")
    implementation("io.grpc:grpc-netty:1.85.1")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

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
