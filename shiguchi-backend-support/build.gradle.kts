// Helpers for the backend behind the gateway: turn the gateway's sort_fields, read_mask and
// page_request into something a query layer can use. Nothing here depends on JPA or on the gateway
// runtime, only on the request/response protos and gRPC's Status, so JDBC, jOOQ and Mongo
// backends can use it too. The dev.appiaworks.shiguchi.backend.jpa package (ProjectionQuery) is the
// one part that needs JPA: the API is compileOnly, so the application brings its own provider and
// the other helpers load without it.
dependencies {
    implementation(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-proto"))
    api(libs.protobuf.java)
    // io.grpc.Status, which carries the INVALID_ARGUMENT these helpers raise.
    api(libs.grpc.stub)
    // Only the @ApiStatus annotation.
    implementation(project(":protobuf-java-extras"))

    // The application brings its own JPA provider; this module only needs the API to compile.
    compileOnly("jakarta.persistence:jakarta.persistence-api")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.hibernate.orm:hibernate-core")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
