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
    // "shiguchi/graphql.proto"` only resolves if the file sits at that path under
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
    implementation("com.google.api.grpc:proto-google-common-protos:2.79.0")
    implementation("io.grpc:grpc-protobuf:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")

    // This module declares its own versions rather than inheriting the Spring BOM, so the BOM is
    // pulled in for the test configuration alone to version JUnit and AssertJ.
    testImplementation(platform(libs.spring.boot4.bom))
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.85.1" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { create("grpc") }
            if (task.sourceSet.name == "main") {
                // The descriptor set, with the comments, that ProtoOptionsReferenceTest renders into
                // docs/reference/proto-options.md.
                task.generateDescriptorSet = true
                task.descriptorSetOptions.path = layout.buildDirectory.file("descriptors/shiguchi.desc").get().asFile.path
                task.descriptorSetOptions.includeSourceInfo = true
            }
        }
    }
}

tasks.test {
    val descriptors = layout.buildDirectory.file("descriptors/shiguchi.desc")
    dependsOn(tasks.named("generateProto"))
    inputs.file(descriptors).withPropertyName("descriptors")
    inputs.file(rootProject.layout.projectDirectory.file("docs/reference/proto-options.md")).optional().withPropertyName("protoOptionsReference")
    systemProperty("shiguchi.descriptors", descriptors.get().asFile.path)
    systemProperty("shiguchi.protoOptionsDoc", rootProject.layout.projectDirectory.file("docs/reference/proto-options.md").asFile.path)
    // -Dshiguchi.docs.update=true rewrites the reference instead of checking it
    System.getProperty("shiguchi.docs.update")?.let { systemProperty("shiguchi.docs.update", it) }
}
