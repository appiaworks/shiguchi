// Shared config for all sample apps. Pin a Java 21 toolchain so the samples build
// reproducibly on any local JDK (Gradle locates/provisions 21) and match the gateway
// libraries' target — Spring Boot 3.4.x tooling cannot read newer bytecode.
subprojects {
    plugins.withId("java") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}
