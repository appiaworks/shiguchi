import java.util.zip.ZipFile

// The HTTP-layer benchmarks on the Boot 4 line: the same sources as shiguchi-benchmarks'
// GatewayHttpBenchmark / GatewayGraphQlBenchmark, compiled and booted against the Boot 4
// auto-configuration (Spring Framework 7, Spring Cloud Gateway 5, DGS 12). Only those suites:
// the serde and dispatch suites measure shared modules and read the same on either line.
plugins {
    id("me.champeau.jmh") version "0.7.3"
    id("com.google.protobuf")
}

val sharedBench = file("../../shiguchi-benchmarks/src/jmh")

sourceSets {
    named("jmh") {
        java {
            // Additive: the protobuf plugin has already added its generated-source dir here.
            srcDir(File(sharedBench, "java"))
            include(
                "dev/appiaworks/shiguchi/bench/GatewayHttpBenchmark.java",
                "dev/appiaworks/shiguchi/bench/GatewayGraphQlBenchmark.java",
                "dev/appiaworks/shiguchi/bench/BenchOrders.java",
                // The include filter applies to every java source dir, the generated protos included.
                "dev/appiaworks/shiguchi/bench/proto/**",
            )
        }
        extensions.getByName<SourceDirectorySet>("proto").srcDir(File(sharedBench, "proto"))
    }
}

dependencies {
    jmhImplementation(platform(libs.spring.boot4.bom))
    jmhImplementation(project(":shiguchi-core"))
    jmhImplementation(project(":shiguchi-rest"))
    jmhImplementation(project(":shiguchi-spring-boot4-autoconfigure"))
    jmhImplementation(project(":shiguchi-graphql-spring-boot4-starter"))
    jmhImplementation(project(":shiguchi-proto"))
    jmhImplementation(libs.grpc.services)
    jmhImplementation(libs.grpc.netty)
    jmhImplementation("io.projectreactor:reactor-core")
    jmhImplementation("io.projectreactor.netty:reactor-netty-http")
    jmhImplementation("org.springframework:spring-webflux")
}

// Same fat-jar care as shiguchi-benchmarks: Spring's per-jar registration files merged, and
// the forked JVM run on the fat jar alone (the plugin would otherwise add every original jar).
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
    eachFile {
        if (isMergedMeta(path) && !file.absolutePath.startsWith(mergedRoot)) exclude()
    }
}

// The sources belong to shiguchi-benchmarks, which formats and checks them; Spotless here
// would only see them through a path outside this project.
tasks.matching { it.name.startsWith("spotless") }.configureEach { enabled = false }

tasks.named<me.champeau.jmh.JMHTask>("jmh") {
    jmhClasspath.setFrom()
    testRuntimeClasspath.setFrom()
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
}

jmh {
    timeUnit = "us"
    resultFormat = "JSON"
    resultsFile = file("build/jmh-results.json")
    profilers.add("gc")
    (project.findProperty("jmh.includes") as String?)?.let { includes.add(it) }
}
