// Helpers for the backend behind the gateway: turn the gateway's sort_fields, read_mask and
// page_request into something a query layer can use. Nothing here depends on JPA or on the gateway
// runtime, only on the request/response protos and gRPC's Status, so JDBC, jOOQ and Mongo
// backends can use it too.
dependencies {
    implementation(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-proto"))
    api(libs.protobuf.java)
    // io.grpc.Status, which carries the INVALID_ARGUMENT these helpers raise.
    api(libs.grpc.stub)
    // Only the @ApiStatus annotation.
    implementation(project(":protobuf-java-extras"))

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
