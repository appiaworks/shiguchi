import org.gradle.internal.os.OperatingSystem

// protoc-gen-javaextras: a protoc plugin that adds Java-typed accessors (LocalDate, Instant,
// BigDecimal, ...) to the classes protoc's Java generator writes. The generated code calls
// ProtoTypes, so consumers put protobuf-java-extras on their compile classpath; the plugin
// itself needs only protobuf-java, to speak protoc's plugin protocol.
plugins {
    // Applied for the test source set only: the end-to-end test compiles real protos through
    // protoc with this plugin and then calls the accessors, which is the one check that proves
    // the inserted code compiles.
    id("com.google.protobuf")
}

dependencies {
    implementation(libs.protobuf.java)

    testImplementation(project(":protobuf-java-extras"))
    // The google/type/*.proto files the test protos import, and their Java classes.
    testImplementation(libs.proto.common.protos)
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// `java -jar protoc-gen-java-extras.jar` is how the Gradle and Maven plugins, and anyone wiring
// protoc by hand, start it.
tasks.named<Jar>("jar") {
    manifest {
        attributes("Main-Class" to "dev.appiaworks.protobuf.extras.protoc.JavaExtrasPlugin")
    }
}

// protoc starts a plugin as a program, so the test build needs one: a script that runs this
// module's freshly compiled classes on the JVM running Gradle.
val launcherDir = layout.buildDirectory.dir("protoc-launcher")
val launcherFile = launcherDir.map {
    it.file(if (OperatingSystem.current().isWindows) "protoc-gen-javaextras.bat" else "protoc-gen-javaextras")
}
val writeProtocLauncher = tasks.register("writeProtocLauncher") {
    val classpath = sourceSets.main.get().runtimeClasspath
    val target = launcherFile
    val javaExe = providers.systemProperty("java.home").map { home ->
        File(home, if (OperatingSystem.current().isWindows) "bin/java.exe" else "bin/java").absolutePath
    }
    val windows = OperatingSystem.current().isWindows
    inputs.files(classpath)
    inputs.property("javaExe", javaExe)
    outputs.file(target)
    doLast {
        val cp = classpath.files.joinToString(File.pathSeparator) { it.absolutePath }
        val file = target.get().asFile
        file.parentFile.mkdirs()
        if (windows) {
            file.writeText("@echo off\r\n\"${javaExe.get()}\" -cp \"$cp\" dev.appiaworks.protobuf.extras.protoc.JavaExtrasPlugin\r\n")
        } else {
            file.writeText("#!/bin/sh\nexec \"${javaExe.get()}\" -cp \"$cp\" dev.appiaworks.protobuf.extras.protoc.JavaExtrasPlugin\n")
            file.setExecutable(true)
        }
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        create("javaextras") { path = launcherFile.get().asFile.absolutePath }
    }
    generateProtoTasks {
        ofSourceSet("test").configureEach {
            dependsOn(writeProtocLauncher)
            inputs.files(sourceSets.main.get().runtimeClasspath)
            plugins {
                create("javaextras") {
                    // Insertion points only resolve against files written to the same directory in
                    // the same protoc run, so the plugin shares the Java generator's output directory.
                    outputSubDir = "java"
                    option(
                        "map=accessors.Sku;dev.appiaworks.protobuf.extras.protoc.fixture.SkuCode;" +
                            "dev.appiaworks.protobuf.extras.protoc.fixture.SkuCode.fromProto;dev.appiaworks.protobuf.extras.protoc.fixture.SkuCode.toProto"
                    )
                }
            }
        }
    }
}

// The end-to-end JSON test compares the inserted writeJson with the writer generated at run
// time, which needs the Class-File API module (and Java 25; the test skips that leg below).
dependencies {
    testRuntimeOnly(project(":protobuf-java-extras-classfile"))
    // JsonFormat is the oracle the inserted writeJson is compared with.
    testImplementation(libs.protobuf.java.util)
}
