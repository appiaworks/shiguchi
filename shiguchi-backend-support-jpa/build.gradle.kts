// JPA on top of shiguchi-backend-support: ProjectionQuery selects only the masked columns, sorts,
// pages (offset or keyset) and builds the response messages, so a list method is a few lines.
dependencies {
    implementation(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-backend-support"))
    // Only the @ApiStatus annotation.
    implementation(project(":protobuf-java-extras"))

    // The application brings its own JPA provider; this module only needs the API to compile.
    compileOnly("jakarta.persistence:jakarta.persistence-api")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.hibernate.orm:hibernate-core")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
