package dev.appiaworks.protobuf.extras.protoc.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Builds a real project that applies the plugin the way a consumer would: by id and version,
 * resolved from a repository (this build publishes itself into a throwaway one first). The
 * project's own Java source calls the generated accessors, so a green {@code compileJava} means
 * protoc ran the plugin, the insertions landed, and the runtime library was on the classpath.
 */
class JavaExtrasGradlePluginFunctionalTest {

    private static final String REPO = System.getProperty("functionalTest.repo").replace('\\', '/');
    private static final String VERSION = System.getProperty("functionalTest.version");

    @TempDir
    Path project;

    @BeforeEach
    void writeProject() throws IOException {
        write("settings.gradle.kts", """
                pluginManagement {
                    repositories {
                        maven { url = uri("%s") }
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                dependencyResolutionManagement {
                    repositories {
                        maven { url = uri("%s") }
                        mavenCentral()
                    }
                }
                rootProject.name = "consumer"
                """.formatted(REPO, REPO));
        write("src/main/proto/acme/order.proto", """
                syntax = "proto3";
                package acme;
                import "google/protobuf/timestamp.proto";
                import "google/type/date.proto";
                import "google/type/decimal.proto";
                option java_multiple_files = true;
                option java_package = "com.acme.proto";
                message Sku { string code = 1; }
                message Order {
                  google.type.Date placed_on = 1;
                  google.protobuf.Timestamp paid_at = 2;
                  google.type.Decimal total = 3;
                  Sku sku = 4;
                }
                """);
    }

    @Test
    @DisplayName(
            "Given a project that only applies the plugin, when it compiles code using the accessors, should succeed with no other wiring")
    void given_pluginApplied_when_compilingCodeThatUsesAccessors_should_succeed() throws IOException {
        write("build.gradle.kts", """
                plugins {
                    `java-library`
                    id("io.github.appiaworks.protoc-java-extras") version "%s"
                }
                dependencies {
                    api("com.google.protobuf:protobuf-java:4.36.2")
                    api("com.google.api.grpc:proto-google-common-protos:2.77.0")
                }
                protobuf {
                    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
                }
                """.formatted(VERSION));
        write("src/main/java/com/acme/Usage.java", """
                package com.acme;

                import com.acme.proto.Order;
                import java.math.BigDecimal;
                import java.time.Instant;
                import java.time.LocalDate;

                class Usage {
                    static LocalDate roundTrip() {
                        Order order = Order.newBuilder()
                                .setPlacedOn(LocalDate.of(2026, 9, 22))
                                .setPaidAt(Instant.EPOCH)
                                .setTotal(new BigDecimal("12.34"))
                                .build();
                        BigDecimal total = order.getTotalAsBigDecimal();
                        Instant paid = order.getPaidAtAsInstant();
                        return order.getPlacedOnAsLocalDate();
                    }
                }
                """);

        BuildResult result = run("compileJava");

        assertThat(result.task(":writeJavaExtrasLauncher").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":generateProto").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        String generated =
                Files.readString(project.resolve("build/generated/sources/proto/main/java/com/acme/proto/Order.java"));
        assertThat(generated)
                .contains("getPlacedOnAsLocalDate()")
                .contains("public Builder setPaidAt(java.time.Instant value)")
                .contains("public void writeJson(com.fasterxml.jackson.core.JsonGenerator gen)")
                .contains("dev.appiaworks.protobuf.extras.JsonWritable,")
                .contains("public Builder mergeJson(com.fasterxml.jackson.core.JsonParser p)")
                .contains("dev.appiaworks.protobuf.extras.JsonReadable,");
    }

    @Test
    @DisplayName(
            "Given disable and map in the javaExtras block, when it compiles, should drop the disabled accessors and add the custom ones")
    void given_disableAndMap_when_compiling_should_followTheOptions() throws IOException {
        write("build.gradle.kts", """
                plugins {
                    `java-library`
                    id("io.github.appiaworks.protoc-java-extras") version "%s"
                }
                dependencies {
                    api("com.google.protobuf:protobuf-java:4.36.2")
                    api("com.google.api.grpc:proto-google-common-protos:2.77.0")
                }
                protobuf {
                    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
                }
                javaExtras {
                    disable("google.type.Decimal")
                    map("acme.Sku", "com.acme.SkuCode", "com.acme.SkuCode.fromProto", "com.acme.SkuCode.toProto")
                    json.set(false)
                }
                """.formatted(VERSION));
        write("src/main/java/com/acme/SkuCode.java", """
                package com.acme;

                import com.acme.proto.Order;
                import com.acme.proto.Sku;

                public record SkuCode(String value) {
                    public static SkuCode fromProto(Sku sku) { return new SkuCode(sku.getCode()); }
                    public static Sku toProto(SkuCode code) { return Sku.newBuilder().setCode(code.value()).build(); }

                    static SkuCode roundTrip() {
                        return Order.newBuilder().setSku(new SkuCode("AB-1")).build().getSkuAsSkuCode();
                    }
                }
                """);

        BuildResult result = run("compileJava");

        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        String generated =
                Files.readString(project.resolve("build/generated/sources/proto/main/java/com/acme/proto/Order.java"));
        assertThat(generated)
                .contains("getSkuAsSkuCode()")
                .doesNotContain("getTotalAsBigDecimal()")
                .doesNotContain("writeJson");
    }

    @Test
    @DisplayName("Given a second build with nothing changed, when it runs, should find code generation up to date")
    void given_nothingChanged_when_buildRunsAgain_should_beUpToDate() throws IOException {
        write("build.gradle.kts", """
                plugins {
                    `java-library`
                    id("io.github.appiaworks.protoc-java-extras") version "%s"
                }
                dependencies {
                    api("com.google.protobuf:protobuf-java:4.36.2")
                    api("com.google.api.grpc:proto-google-common-protos:2.77.0")
                }
                protobuf {
                    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
                }
                """.formatted(VERSION));

        run("compileJava");
        BuildResult second = run("compileJava");

        assertThat(second.task(":writeJavaExtrasLauncher").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(second.task(":generateProto").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    private BuildResult run(String... tasks) {
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withArguments(append(tasks, "--stacktrace"))
                .forwardOutput()
                .build();
    }

    private static String[] append(String[] tasks, String extra) {
        String[] all = new String[tasks.length + 1];
        System.arraycopy(tasks, 0, all, 0, tasks.length);
        all[tasks.length] = extra;
        return all;
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
