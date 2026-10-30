// The protoc-java-extras Gradle plugin is a io.github.appiaworks artifact like the libraries, so it
// resolves from the same place (mavenLocal after ./gradlew publishToMavenLocal) and at the same
// -PgatewayVersion. Everything else comes from the Gradle Plugin Portal as usual.
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
    plugins {
        id("io.github.appiaworks.protoc-java-extras") version
            providers.gradleProperty("gatewayVersion").getOrElse("0.14.1")
    }
}

rootProject.name = "shiguchi-samples"

// Auto-provision the Java 21 toolchain when it is not already installed, so the samples
// build reproducibly regardless of the developer's / runner's default JDK.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Samples are a standalone consumer build: they resolve the published io.github.appiaworks:*
// artifacts (from mavenLocal in CI / dev, or Maven Central once released) plus their transitive
// Spring/gRPC deps from Maven Central.
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

include(
    "sample-full",
    "sample-rest-gateway",
    "sample-graphql-gateway",
    "sample-grpc-proxy",
    "sample-grpc-ui-standalone",
    "sample-openfga-authz",
    "sample-opa-authz",
    "sample-merge-gateway",
    "sample-rest-downstream",
    "sample-avro-downstream",
    "sample-rate-limited-gateway",
    "sample-discovery-gateway",
    "sample-static-schema",
    "sample-secured-gateway",
    "sample-validation",
    "sample-protobuf-java-extras"
)

// The BFF sample is three parts under one folder; Gradle sees the two JVM parts as flat projects.
// The third part (web/) is an npm project that Gradle never touches.
include("sample-bff-issuer", "sample-bff-gateway")

project(":sample-bff-issuer").projectDir = file("sample-bff-spa/issuer")

project(":sample-bff-gateway").projectDir = file("sample-bff-spa/gateway")