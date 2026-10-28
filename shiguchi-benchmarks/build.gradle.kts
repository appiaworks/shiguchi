import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.internal.os.OperatingSystem
import java.util.zip.ZipFile

plugins {
    id("me.champeau.jmh") version "0.7.3"
    // Compiles src/jmh/proto so the Tier 1 (typed writer over generated classes) leg
    // has a stub class to find, exactly as a STATIC-mode service would. The protos also go
    // through protoc-gen-java-extras (below), so the stub carries its writeJson/mergeJson like
    // a service built with the plugin: Tier 1 and ProtoJson's typed legs then run on Java 21 too.
    id("com.google.protobuf")
}

// protoc starts a plugin as a program: a script that runs the plugin module's classes on the
// JVM running Gradle, as the plugin module's own test build does.
val protocJavaExtras: Configuration by configurations.creating

val launcherFile = layout.buildDirectory.file(
    if (OperatingSystem.current().isWindows) "protoc-launcher/protoc-gen-javaextras.bat"
    else "protoc-launcher/protoc-gen-javaextras"
)
val writeProtocLauncher = tasks.register("writeProtocLauncher") {
    val classpath = protocJavaExtras
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

dependencies {
    jmhImplementation(platform(libs.spring.boot.bom))
    jmhImplementation(project(":shiguchi-core"))
    jmhImplementation(project(":shiguchi-rest"))
    jmhImplementation(project(":shiguchi-grpc-proxy"))
    // Avro upstream codec vs the JSON path (AvroCodecBenchmark).
    jmhImplementation(project(":shiguchi-avro"))
    // Federation batch loaders (FederationBatchLoaderBenchmark).
    jmhImplementation(project(":shiguchi-graphql"))
    // The push surface (PushBenchmark): engine and SSE delivery.
    jmhImplementation(project(":shiguchi-cursus"))
    jmhImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    jmhImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    jmhImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    jmhImplementation("org.postgresql:r2dbc-postgresql")
    jmhImplementation("io.zonky.test:embedded-postgres:2.2.2")
    jmhImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:18.6.0"))
    jmhRuntimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-arm64v8")
    // The JDBC engine's trial: Spring's JdbcTemplate over an in-memory H2.
    jmhImplementation("org.springframework:spring-jdbc")
    jmhImplementation("com.h2database:h2")
    // Version managed by the Spring Boot BOM above — do not pin here.
    jmhImplementation("com.fasterxml.jackson.core:jackson-databind")
    jmhImplementation(libs.grpc.inprocess)
    // The merge benchmark measures the streaming path, which is Flux-based.
    jmhImplementation("io.projectreactor:reactor-core")
    // mixedPage: one REST_DOWNSTREAM source served by a reactor-netty stub, called over WebClient.
    jmhImplementation("io.projectreactor.netty:reactor-netty-http")
    jmhImplementation("org.springframework:spring-webflux")
    // GatewayHttpBenchmark boots the Boot 3 gateway on a port and calls it over HTTP, against a
    // reflection-served backend on a socket.
    jmhImplementation(project(":shiguchi-spring-boot3-autoconfigure"))
    jmhImplementation(libs.grpc.services)
    jmhImplementation(libs.grpc.netty)
    // GatewayGraphQlBenchmark: the same gateway with the GraphQL surface on (DGS over spring-graphql),
    // against a backend whose protos carry (shiguchi.api.graphql) / (shiguchi.api.graphql_field) options.
    jmhImplementation(project(":shiguchi-graphql-spring-boot3-starter"))
    // graphql.proto on src/jmh/proto's include path (the protobuf plugin extracts it from the jar).
    jmhImplementation(project(":shiguchi-proto"))

    protocJavaExtras(project(":protoc-gen-java-extras"))
}

// The JMH jar flattens every dependency into one archive. Spring registers auto-configurations
// through one file per jar under the same path, and ServiceLoader providers likewise, so those
// files must be concatenated across jars, not taken from whichever jar came first — or the
// gateway's auto-configurations (GatewayHttpBenchmark) and the serde code generator are missing.
val springMetaDir = layout.buildDirectory.dir("spring-meta")
val mergeSpringMeta by tasks.registering {
    val classpath = configurations.named("jmhRuntimeClasspath")
    val out = springMetaDir
    inputs.files(classpath)
    outputs.dir(out)
    doLast {
        val merged = linkedMapOf<String, StringBuilder>()
        classpath.get().files.filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && isMergedMeta(it.name) }
                    .forEach { entry ->
                        val text = zip.getInputStream(entry).bufferedReader().readText()
                        // Two newlines: a file may end on a continuation backslash, which would
                        // otherwise fuse its last value with the next jar's first key.
                        merged.getOrPut(entry.name) { StringBuilder() }.append(text).append("\n\n")
                    }
            }
        }
        val root = out.get().asFile
        root.deleteRecursively()
        merged.forEach { (name, text) ->
            val file = File(root, name)
            file.parentFile.mkdirs()
            // spring.factories is a properties file: the same key appears in many jars and a later
            // one would replace, not extend, an earlier one — join each key's values instead.
            if (name.endsWith(".factories")) {
                val byKey = linkedMapOf<String, LinkedHashSet<String>>()
                text.toString().replace("\\\r\n", "").replace("\\\n", "").lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
                    .forEach { line ->
                        val key = line.substringBefore('=').trim()
                        val values = line.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        byKey.getOrPut(key) { LinkedHashSet() }.addAll(values)
                    }
                file.writeText(byKey.entries.joinToString("\n") { (k, v) -> k + "=" + v.joinToString(",") } + "\n")
            } else {
                file.writeText(text.toString())
            }
        }
    }
}

fun isMergedMeta(path: String): Boolean =
    path == "META-INF/spring.factories" ||
        path.startsWith("META-INF/services/") ||
        (path.startsWith("META-INF/spring/") && (path.endsWith(".imports") || path.endsWith(".factories")))

tasks.named<Jar>("jmhJar") {
    dependsOn(mergeSpringMeta)
    from(springMetaDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val mergedRoot = springMetaDir.get().asFile.absolutePath
    // Keep only the merged copy of each registration file; the per-jar originals are dropped.
    eachFile {
        if (isMergedMeta(path) && !file.absolutePath.startsWith(mergedRoot)) exclude()
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    plugins {
        create("javaextras") { path = launcherFile.get().asFile.absolutePath }
    }
    generateProtoTasks {
        ofSourceSet("jmh").configureEach {
            dependsOn(writeProtocLauncher)
            inputs.files(protocJavaExtras)
            plugins {
                // Insertion points resolve only against files written to the same directory in
                // the same protoc run, so the plugin shares the Java generator's output directory.
                create("javaextras") { outputSubDir = "java" }
            }
        }
    }
}

// The plugin runs the forked JVM on the fat jar AND every original jar. Resources a library finds
// with classpath*: (DGS's META-INF/schema/*.graphqls) then come up twice, and DGS refuses a
// schema that defines ErrorDetail twice. Run on the fat jar alone, as `java -jar` does.
tasks.named<me.champeau.jmh.JMHTask>("jmh") {
    jmhClasspath.setFrom()
    testRuntimeClasspath.setFrom()
}

jmh {
    // Per-class @Warmup / @Measurement / @Fork annotations are the source of truth for
    // iteration counts (they vary by benchmark). Only suite-wide settings live here.
    timeUnit = "us"
    resultFormat = "JSON"
    resultsFile = file("build/jmh-results.json")

    // GC profiler surfaces allocation rate — the whole point of the Tier-2 serializers is
    // to avoid allocations, which is invisible in throughput numbers alone.
    profilers.add("gc")

    // Run a single benchmark (or a regex subset) without learning JMH:
    //   ./gradlew :shiguchi-benchmarks:jmh -Pjmh.includes='SerdeBenchmark.tier2_toMap_flat'
    //   ./gradlew :shiguchi-benchmarks:jmh -Pjmh.includes='Timestamp'
    (project.findProperty("jmh.includes") as String?)?.let {
        includes.add(it)
    }
    // Pin @Param values, e.g. -Pjmh.params='engine=REDIS;archive=true,false' (key=v1,v2 pairs split on ';').
    (project.findProperty("jmh.params") as String?)?.split(';')?.forEach { pair ->
        val (name, values) = pair.split('=', limit = 2)
        benchmarkParameters.put(name.trim(), objects.listProperty<String>().value(values.split(',').map { it.trim() }))
    }
}

// The Benchmarks workflow feeds the JMH JSON to benchmark-action/github-action-benchmark, which
// treats every score as "smaller is better", so a throughput gain (more ops/us) was reported as
// a regression. This rewrites Throughput results as time per op (1/score, error scaled by the
// same derivative) and relabels them avgt; time-mode results pass through untouched. It lives
// here rather than as a python3 heredoc in the workflow so the job needs nothing beyond
// what setup-java installs: Gradle's bundled Groovy JSON is enough.
val jmhNormalise by tasks.registering {
    group = "verification"
    description = "Rewrites build/jmh-results.json with throughput scores inverted to time per op"
    val src = layout.buildDirectory.file("jmh-results.json")
    val dst = layout.buildDirectory.file("jmh-results-normalised.json")
    inputs.file(src)
    outputs.file(dst)
    doLast {
        val units = mapOf(
            "ops/ns" to "ns/op", "ops/us" to "us/op", "ops/ms" to "ms/op", "ops/s" to "s/op", "ops/min" to "min/op",
        )
        @Suppress("UNCHECKED_CAST")
        val results = JsonSlurper().parse(src.get().asFile) as List<MutableMap<String, Any?>>
        var inverted = 0
        for (r in results) {
            @Suppress("UNCHECKED_CAST")
            val m = r["primaryMetric"] as? MutableMap<String, Any?> ?: continue
            val score = (m["score"] as? Number)?.toDouble() ?: continue
            if (r["mode"] != "thrpt" || score == 0.0) continue
            val error = (m["scoreError"] as? Number)?.toDouble() ?: 0.0
            m["scoreError"] = error / (score * score)
            m["score"] = 1.0 / score
            val unit = m["scoreUnit"]?.toString()
            m["scoreUnit"] = units[unit] ?: "1/($unit)"
            m.remove("scorePercentiles")
            m.remove("rawData")
            r["mode"] = "avgt"
            inverted++
        }
        dst.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(results)))
        logger.lifecycle("normalised ${results.size} results ($inverted throughput inverted) -> ${dst.get().asFile}")
    }
}

// The benchmarks are compiled on every build even though they only run on demand. They reach
// into internal classes of the modules they measure, so a refactor there breaks them silently
// otherwise: nothing else compiles this source set, and the Benchmarks workflow is the first to
// find out, weeks later.
tasks.named("check") { dependsOn("compileJmhJava") }
