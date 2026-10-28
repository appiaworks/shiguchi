// Plain Java, no gateway, no Spring, no gRPC: protobuf-java-extras on its own.
//
// The one plugin below does all the proto wiring. It applies com.google.protobuf, runs
// protoc-gen-java-extras next to the Java generator, and adds io.github.appiaworks:protobuf-java-extras
// to the classpath. Its version comes from settings.gradle.kts (-PgatewayVersion=...).
plugins {
    application
    id("io.github.appiaworks.protoc-java-extras")
}

group = "dev.appiaworks.shiguchi.sample"

version = "0.13.0"

val gatewayVersion = providers.gradleProperty("gatewayVersion").getOrElse("0.13.0")

dependencies {
    // Java 25+: ProtoJson generates a serializer per message type with the Class-File API.
    // Inert on an older JVM, where the same JSON comes from the reflective paths (step 10).
    runtimeOnly("io.github.appiaworks:protobuf-java-extras-classfile:$gatewayVersion")
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    // google/type/*.proto and their Java classes.
    implementation("com.google.api.grpc:proto-google-common-protos:2.77.0")
    // Only for the Serializer / Deserializer interfaces in com.acme.shop.kafka; no broker is needed.
    implementation("org.apache.kafka:kafka-clients:4.3.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
}

javaExtras {
    // order.getLines(0).getSkuAsSkuCode(), lineBuilder.setSku(new SkuCode("AB-1")).
    map("acme.shop.Sku", "com.acme.shop.SkuCode", "com.acme.shop.SkuCode.fromProto", "com.acme.shop.SkuCode.toProto")
}

application { mainClass = "com.acme.shop.Main" }

// The samples build on a Java 21 toolchain. To see the generated serializers (step 10), run the
// program on a newer JDK: ./gradlew -p samples sample-protobuf-java-extras:run -PrunJava=25
val runJava = providers.gradleProperty("runJava").map(JavaLanguageVersion::of)
tasks.named<JavaExec>("run") {
    if (runJava.isPresent) javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(runJava) })
}

tasks.withType<Test> { useJUnitPlatform() }
