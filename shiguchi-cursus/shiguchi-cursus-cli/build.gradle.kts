plugins {
    alias(libs.plugins.shadow)
}

// cursus: a command line, not a library. No Spring on the classpath (start-up time, jar size):
// picocli, the JDK's HTTP client and WebSocket, the generated gRPC stubs over grpc-netty-shaded and
// Jackson. It is deliberately outside the apiCompatibility gate and the BOM; the fat jar
// (cursus-<version>-all.jar) is what users run.
dependencies {
    implementation(platform(libs.spring.boot.bom))

    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)

    implementation(project(":shiguchi-proto"))
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.java.util)
    implementation(libs.proto.common.protos)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    // The channel builder is used directly (TLS, insecure trust).
    implementation(libs.grpc.netty.shaded)

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "dev.appiaworks.shiguchi.cursus.cli.Cursus",
            "Implementation-Title" to "cursus",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("cursus")
    archiveClassifier.set("all")
    // grpc-netty-shaded and the grpc services are found through META-INF/services: merge, never overwrite.
    mergeServiceFiles()
    // Nothing is relocated (grpc's netty is already shaded); signatures of merged jars would not verify.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes("Main-Class" to "dev.appiaworks.shiguchi.cursus.cli.Cursus")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Aproject=${project.name}")
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
