// The code generator behind protobuf-java-extras' compiled serializers, written against the
// Class-File API (java.lang.classfile, final in Java 24) and so compiled for Java 25 — the one
// module in the repository that is not Java 21 bytecode. protobuf-java-extras finds it through
// ServiceLoader; on a JVM below 25 the provider does not load, which the loader treats as "no
// generator", and the serde runs on its wire transcoder and reflective paths instead.
dependencies {
    api(project(":protobuf-java-extras"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.release.set(25)
}

// Gradle derives the variant's JVM-version attribute from --release and would then refuse this
// jar on a Java 21 consumer's classpath. That is the one place it belongs: inert there, loaded
// on 25+. Advertise it as 21-compatible, which is what the ServiceLoader guard makes true.
configurations.matching { it.name == "apiElements" || it.name == "runtimeElements" }.configureEach {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
