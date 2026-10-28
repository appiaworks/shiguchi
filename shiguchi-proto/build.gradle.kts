plugins {
    id("com.google.protobuf")
}

// Include .proto sources in the published jar so downstream consumers
// can extract them for their own protoc compilation.
tasks.named<ProcessResources>("processResources") {
    from("src/main/proto")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// The same protos again, as a plain zip published under the `protos` classifier.
//
// The jar already carries them and Gradle's protobuf plugin extracts them from a dependency
// automatically, which covers JVM consumers. Everyone else -- a Go, Rust, Python or buf
// toolchain that wants these on its protoc include path -- has no reason to know what a Maven
// jar is. A zip at a predictable coordinate is something any build can fetch and unzip.
val protosZip by tasks.registering(Zip::class) {
    archiveClassifier.set("protos")
    from("src/main/proto")
    // The archive must reproduce the import paths verbatim: `import
    // "shiguchi/api/graphql.proto"` only resolves if the file sits at that path under
    // whatever directory the consumer unzips into and puts on -I.
    includeEmptyDirs = false
}

artifacts {
    archives(protosZip)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(protosZip)
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("com.google.api.grpc:proto-google-common-protos:2.77.0")
    implementation("io.grpc:grpc-protobuf:1.84.0")
    implementation("io.grpc:grpc-stub:1.84.0")

    // This module declares its own versions rather than inheriting the Spring BOM, so the BOM is
    // pulled in for the test configuration alone to version JUnit and AssertJ.
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
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
        }
    }
}