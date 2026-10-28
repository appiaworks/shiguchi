// The Gradle side of protoc-gen-javaextras: apply one plugin id and the generated proto classes
// gain Java-typed accessors. It applies com.google.protobuf, resolves the protoc plugin, writes
// the launcher protoc needs, wires it into every generateProto task and adds the runtime library
// the generated code calls.
plugins {
    `java-gradle-plugin`
}

dependencies {
    // A real dependency, not compileOnly: this plugin applies com.google.protobuf itself, and its
    // classes must share a class loader with the extension types they configure.
    implementation("com.google.protobuf:protobuf-gradle-plugin:0.10.0")

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins {
        create("javaExtras") {
            id = "io.github.appiaworks.protoc-java-extras"
            implementationClass = "dev.appiaworks.protobuf.extras.protoc.gradle.JavaExtrasGradlePlugin"
            displayName = "protoc-gen-javaextras"
            description = "Adds Java-typed accessors (LocalDate, Instant, BigDecimal, ...) to protoc-generated classes."
        }
    }
}

// The plugin resolves protoc-gen-java-extras and protobuf-java-extras at its own version, which
// it reads from this resource.
tasks.named<ProcessResources>("processResources") {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("**/plugin.properties") { expand("version" to version) }
}

// The functional test builds a real project against this plugin. It resolves the plugin, the
// protoc plugin and the runtime library the way a consumer build would, from a repository: a
// throwaway one under the root build directory, filled just before the test runs.
val functionalTestRepo = rootProject.layout.buildDirectory.dir("functional-test-repo")
tasks.named<Test>("test") {
    dependsOn(
        "publishAllPublicationsToFunctionalTestRepository",
        ":protoc-gen-java-extras:publishAllPublicationsToFunctionalTestRepository",
        ":protobuf-java-extras:publishAllPublicationsToFunctionalTestRepository",
    )
    systemProperty("functionalTest.repo", functionalTestRepo.get().asFile.absolutePath)
    systemProperty("functionalTest.version", project.version.toString())
}
