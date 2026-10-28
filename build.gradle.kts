import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.authentication.http.HttpHeaderAuthentication

plugins {
    base
    id("com.google.protobuf") version "0.10.0" apply false
    id("com.netflix.dgs.codegen") version "8.7.0" apply false
    id("com.github.node-gradle.node") version "7.1.0" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("com.diffplug.spotless") version "8.10.4" apply false
    id("me.champeau.gradle.japicmp") version "0.4.6" apply false
}

allprojects {
    group = "io.github.appiaworks"
    version = "0.13.0"
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// java-platform (bom) and java-library are mutually exclusive — skip bom
configure(subprojects.filter { it.name != "shiguchi-bom" }) {
    apply(plugin = "java-library")
    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // Keep parameter names in the bytecode. Actuator reads them to bind @Selector on a read
    // operation (an @Endpoint with a selector fails to register without this), and Spring's
    // constructor binding and @PathVariable/@RequestParam without an explicit name rely on the
    // same thing — so this belongs to every module, not only the one that first needed it.
    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-parameters")
        // Built on JDK 25, published for Java 21 consumers. --release (not just source/target)
        // compiles against the Java 21 API, so a call to anything added in 22-25 fails the build
        // here instead of failing at runtime for a user still on 21.
        options.release.set(21)
    }
    // Coverage is measured, not enforced. There is no minimum to clear: a ratchet on a number
    // invites tests written to move the number, and the interesting question here is which
    // branches of the transcoder and the route loaders are exercised, not what percentage is.
    // Opt in with -Pcoverage: the agent slows every test JVM and changes the test tasks' cache
    // key, so a plain build neither pays for it nor misses the build cache. CI passes it on a
    // dispatch with the coverage input and uploads the report.
    val coverage = providers.gradleProperty("coverage").isPresent
    if (coverage) {
        apply(plugin = "jacoco")
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        if (coverage) {
            finalizedBy(tasks.withType<JacocoReport>())
        }
        // -PtestJavaVersion=21 runs the tests on that JDK (found through Gradle's toolchain
        // detection, e.g. -Porg.gradle.java.installations.paths=<jdk>) while the build stays on
        // the JDK running Gradle: the generated serde tiers need Java 25 at run time, and this
        // is how CI proves the fallback paths on 21. Tests that need the generator skip themselves.
        (project.findProperty("testJavaVersion") as String?)?.toIntOrNull()?.let { version ->
            javaLauncher.set(project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
                languageVersion.set(JavaLanguageVersion.of(version))
            })
        }
    }
    tasks.withType<JacocoReport> {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true) // what report tooling reads
            html.required.set(true) // what a human reads
        }
    }
    // Generics like Map<String,Object> in doc comments trip the stricter doclint HTML check
    // (notably on newer JDKs), which would fail the javadoc jar built for publishing. Disable
    // doclint so releases generate reproducibly regardless of the local/CI JDK.
    tasks.withType<Javadoc> {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    apply(plugin = "com.diffplug.spotless")
    // Ratchet: only files changed relative to the base branch are formatted/checked, so adopting
    // Spotless does not force a repo-wide reformat commit.
    //
    // The base is overridable because it is wrong for any branch that is not cut from main. On a
    // release/* branch, diffing against origin/main marks every file that differs between the two
    // lines as "changed" and demands a reformat of code the change never touched. CI passes the
    // PR's own base branch via -PspotlessRatchetFrom.
    val ratchetBase = (findProperty("spotlessRatchetFrom") as String?)?.takeIf { it.isNotBlank() }
        ?: "origin/main"
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        ratchetFrom(ratchetBase)
        java {
            targetExclude("**/build/**")
            palantirJavaFormat()
        }
    }
}

// Apply publishing to all publishable modules (everything except samples and benchmarks)
val samplesModules = setOf("sample-full", "sample-rest-gateway", "sample-graphql-gateway",
                           "sample-grpc-proxy", "sample-grpc-ui-standalone", "shiguchi-benchmarks",
                           "shiguchi-benchmarks-boot4")

configure(subprojects.filter { it.name !in samplesModules }) {
    apply(plugin = "com.vanniktech.maven.publish")

    // Apache-2.0 §4(a)/(d): every distributed artifact carries the license and the
    // attribution notices. Packaged under META-INF so they survive shading/repackaging.
    tasks.withType<Jar>().configureEach {
        metaInf {
            from(rootProject.layout.projectDirectory.file("LICENSE"))
            from(rootProject.layout.projectDirectory.file("NOTICE"))
        }
    }

    // Maven Central (Sonatype Central Portal) is the only publish target. It activates only when
    // credentials are present (mavenCentralUsername Gradle property or
    // ORG_GRADLE_PROJECT_mavenCentralUsername env var), so local builds and publishToMavenLocal
    // need no key. Signing uses the in-memory GPG key the vanniktech plugin reads from
    // signingInMemoryKey / signingInMemoryKeyPassword.
    val centralConfigured = providers.gradleProperty("mavenCentralUsername")
        .orElse(providers.environmentVariable("ORG_GRADLE_PROJECT_mavenCentralUsername"))
        .isPresent

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        if (centralConfigured) {
            publishToMavenCentral()
            signAllPublications()
        }
        coordinates(project.group.toString(), project.name, project.version.toString())

        pom {
            name.set(project.name)
            description.set("Shiguchi — ${project.name}")
            url.set("https://github.com/appiaworks/shiguchi")
            licenses {
                license {
                    name.set("Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("appiaworks")
                    name.set("Shiguchi contributors")
                    url.set("https://github.com/appiaworks/shiguchi")
                }
            }
            scm {
                url.set("https://github.com/appiaworks/shiguchi")
                connection.set("scm:git:git://github.com/appiaworks/shiguchi.git")
                developerConnection.set("scm:git:ssh://git@github.com/appiaworks/shiguchi.git")
            }
        }
    }
}

// The functional test of the protoc-java-extras Gradle plugin builds a consumer project that
// resolves the plugin, the protoc plugin and the runtime library from a repository, as a real
// build would. These three publish into a throwaway one under build/ for it; nothing else does,
// and nothing leaves the machine.
val functionalTestModules = setOf(
    "protobuf-java-extras",
    "protoc-gen-java-extras",
    "protoc-gen-java-extras-gradle-plugin",
)
configure(subprojects.filter { it.name in functionalTestModules }) {
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "functionalTest"
                url = uri(rootProject.layout.buildDirectory.dir("functional-test-repo"))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Binary compatibility gate (japicmp)
//
// Every published module is compared against the last release. A change that would break a
// consumer's compiled code fails the build, so an accidental signature change is caught in the
// PR rather than by whoever upgrades.
//
// The gate is deliberately narrow: it only fails on types marked
// @ApiStatus(STABLE) — the extension points applications implement — and on the
// configuration model they bind YAML to. Everything else is internal by default (see
// dev.appiaworks.protobuf.extras.api.ApiStatus) and free to change, which is what keeps the gate useful
// rather than noise to be suppressed.
//
// Run locally with `./gradlew apiCompatibility`. `-PapiBaseline=0.3.0` compares against a
// different release; `-PapiBaseline=none` skips it (for a release after a deliberate break, where
// the CHANGELOG note is the answer instead).
// ---------------------------------------------------------------------------------------------

// The baseline is the last release: every published module is compared against it on each build.
//
// It is "none" until the first io.github.appiaworks release publishes: no earlier release exists
// under these coordinates, so there is nothing to compare against. That release becomes the baseline.
//
// Note for the next release: the bump belongs in the first commit AFTER the tag publishes, never
// in the release commit. `./gradlew build` runs this gate, the release workflow runs that build
// to produce the artefacts, and a baseline that cannot be resolved fails rather than passing
// quietly — so naming a version before it exists on Maven Central fails the build meant to create it.
val apiBaselineVersion = providers.gradleProperty("apiBaseline").orElse("none")

// The published baselines live on Maven Central.
// Kept as its own repository with a content filter so it is consulted only for this group's
// released artifacts and never becomes a general-purpose fallback for ordinary dependencies.
val apiBaselineRepo: RepositoryHandler.() -> Unit = {
    ivy {
        name = "apiBaseline"
        url = uri("https://repo1.maven.org/maven2/")
        // An Ivy repository with an explicit Maven-shaped layout: it fetches the published jar
        // from its real path while letting the coordinates Gradle sees carry a different group
        // (see the note on baselineGroup below). metadataSources(artifact()) means "there is no
        // Ivy descriptor, just take the jar", which is all the gate compares.
        patternLayout {
            artifact("io/github/appiaworks/[module]/[revision]/[module]-[revision](-[classifier]).[ext]")
        }
        metadataSources { artifact() }
        content { includeGroup("api-baseline.io.github.appiaworks") }
    }
}

// Modules whose public surface consumers compile against. The starters are excluded: they are
// dependency-only POMs with no classes of their own, as is the BOM.
//
// protobuf-java-extras joined with the 0.8.0 baseline, the first release that publishes it, and
// protobuf-java-extras-classfile with 0.9.0. Their types are EXPERIMENTAL or INTERNAL, which the
// gate ignores, so listing them only starts the comparison.
val apiCheckedModules = setOf(
    "protobuf-java-extras",
    "protobuf-java-extras-classfile",
    "shiguchi-core",
    "shiguchi-rest",
    "shiguchi-graphql",
    "shiguchi-openapi",
    "shiguchi-avro",
    "shiguchi-validation",
    "shiguchi-grpc-proxy",
    "shiguchi-ui",
)

val apiCompatibility by tasks.registering {
    group = "verification"
    description = "Checks every published module's public API against the last release."
}

configure(subprojects.filter { it.name in apiCheckedModules }) {
    apply(plugin = "me.champeau.gradle.japicmp")

    repositories(apiBaselineRepo)

    val baseline = apiBaselineVersion.get()
    if (baseline == "none") return@configure

    // The repo keeps its version at the last release while unreleased work accumulates, so the
    // baseline's coordinates are usually the project's own. A normal dependency would then be
    // substituted with the local project and the gate would compare the build against itself,
    // passing no matter what changed. Renaming the group for resolution keeps Gradle from
    // recognising it as this project, and a repository-level artifact rule maps it back to the
    // real coordinates when fetching.
    val baselineGroup = "api-baseline.${project.group}"
    val baselineArtifacts = configurations.create("apiBaseline") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    dependencies.add(baselineArtifacts.name, "$baselineGroup:${project.name}:$baseline")

    // Two tasks, not one: japicmp ANDs annotationIncludes with packageIncludes, so a single task
    // given both filters matches only classes that satisfy both — which here is nothing, and the
    // gate would pass everything silently. Each filter gets its own comparison instead.
    //
    // "stableApi" covers the extension points applications implement, wherever they live;
    // "configModel" covers the properties classes YAML binds to, which are equally part of the
    // contract (renaming a getter breaks every consumer's configuration) and easy to change by
    // accident while refactoring.
    fun registerCheck(name: String, what: String, configure: me.champeau.gradle.japicmp.JapicmpTask.() -> Unit) =
        tasks.register<me.champeau.gradle.japicmp.JapicmpTask>(name) {
            group = "verification"
            description = "Compares this module's $what against $baseline."

            // Archives are what gets compared; the classpaths are only how japicmp resolves the
            // supertypes and parameter types it meets along the way. Without the dependencies it
            // aborts on the first Spring or gRPC type it cannot load.
            oldArchives.from(baselineArtifacts)
            newArchives.from(tasks.named("jar"))
            oldClasspath.from(configurations.named("compileClasspath"))
            newClasspath.from(configurations.named("compileClasspath"))
            // Optional dependencies of sibling modules can still go missing. A type japicmp cannot
            // see is not evidence of a break, and the report lists what it could not load.
            ignoreMissingClasses.set(true)

            onlyModified.set(true)
            failOnSourceIncompatibility.set(true)


            richReport {
                title.set("${project.name} — $what vs $baseline")
                destinationDir.set(layout.buildDirectory.dir("reports/japicmp").get().asFile)
                reportName.set("$name.html")
            }

            // A baseline that cannot be resolved must not silently pass the gate.
            doFirst {
                if (baselineArtifacts.isEmpty) {
                    throw GradleException(
                        "No baseline artifact for ${project.name}:$baseline, so the gate cannot run. " +
                            "Pass -PapiBaseline=<version> to compare against a different release, " +
                            "or -PapiBaseline=none to skip it deliberately."
                    )
                }
            }

            configure()
        }

    // japicmp reads the marker off the class file, which is why ApiStatus is @Retention(CLASS).
    val checkStableApi = registerCheck("checkStableApi", "@ApiStatus types") {
        annotationIncludes.set(listOf("@dev.appiaworks.protobuf.extras.api.ApiStatus"))
    }
    val checkConfigModel = registerCheck("checkConfigModel", "configuration model") {
        packageIncludes.set(listOf("dev.appiaworks.shiguchi.config"))
    }

    // japicmp judges compatibility from a caller's point of view, where adding a method to an
    // interface is harmless — its report literally says "added in source compatible way". For an
    // SPI the judgement is backwards: these interfaces exist to be *implemented* by applications,
    // so a new abstract method breaks every implementor's build. japicmp offers no setting to
    // promote that to a failure, so it is checked directly against the two jars.
    //
    // Adding a `default` method stays allowed. That is how an SPI is meant to grow.
    val checkStableAbstractMethods = tasks.register("checkStableAbstractMethods") {
        group = "verification"
        description = "Fails when an @ApiStatus(STABLE) interface gained an abstract method since $baseline."

        val baselineJars = baselineArtifacts
        val currentJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
        // Resolved at configuration time: a doLast block must not resolve a configuration.
        val compileClasspathFiles = configurations.named("compileClasspath").get().files
        inputs.files(baselineJars)
        inputs.file(currentJar)
        outputs.upToDateWhen { true }

        doLast {
            if (baselineJars.isEmpty) {
                throw GradleException(
                    "No baseline artifact for ${project.name}:$baseline, so the gate cannot run."
                )
            }
            val added = StableSpi.addedAbstractMethods(
                baselineJars.singleFile,
                currentJar.get().asFile,
                compileClasspathFiles,
            )
            if (added.isNotEmpty()) {
                throw GradleException(
                    "Abstract method(s) added to @ApiStatus(STABLE) interface(s) since $baseline:\n" +
                        added.joinToString("\n") { "  - $it" } +
                        "\n\nEvery application implementing the interface stops compiling. Give the method " +
                        "a default implementation, or mark the type EXPERIMENTAL if it is not ready to be " +
                        "a stable extension point."
                )
            }
        }
    }

    apiCompatibility.configure { dependsOn(checkStableApi, checkConfigModel, checkStableAbstractMethods) }
    tasks.named("check") { dependsOn(checkStableApi, checkConfigModel, checkStableAbstractMethods) }
}

