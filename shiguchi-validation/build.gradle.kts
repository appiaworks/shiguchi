import org.gradle.api.tasks.SourceSetContainer

// ConfigurationReferenceDriftTest reuses core's ConfigurationReferenceChecker.
evaluationDependsOn(":shiguchi-core")

// Resolved outside the dependencies block: there `project(...)` is a dependency factory, not a Project.
val coreTestOutput = project(":shiguchi-core").extensions.getByType<SourceSetContainer>()["test"].output

dependencies {
    implementation(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-core"))
    api(libs.protovalidate)

    compileOnly(project(":shiguchi-rest"))
    compileOnly(project(":shiguchi-grpc-proxy"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(coreTestOutput)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ConfigurationReferenceDriftTest reads the hand-written reference; rerun when the docs change.
tasks.test {
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/reference")).withPropertyName("configurationReference")
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/guides")).withPropertyName("guides")
}
