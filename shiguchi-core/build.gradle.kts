dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shiguchi-proto"))
    // The JSON serde and type converters, usable without the gateway.
    api(project(":protobuf-java-extras"))
    api(libs.protobuf.java)
    api(libs.protobuf.java.util)
    api(libs.proto.common.protos)
    api(libs.grpc.services)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    // SCG GrpcSslConfigurer needs unshaded grpc-netty at runtime — expose to consumers.
    runtimeOnly(libs.grpc.netty)
    api(libs.caffeine)

    implementation(libs.spring.boot.autoconfigure)
    // Core is not a web module: it needs PathPattern (spring-web) to match route templates and
    // Flux (reactor-core) to merge ordered streams, not a server, a codec stack or WebFlux. The
    // starter pulled all of that in, so anything depending on core inherited an embedded Netty
    // server it may not want. The WebFlux modules declare the starter themselves.
    implementation(libs.spring.web)
    implementation(libs.reactor.core)
    // Was arriving transitively through the starter, which is why it was never declared.
    implementation(libs.slf4j.api)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    // The compiled serde tiers (Tier 1 typed writers, Tier 2 serializers): generated with the
    // ClassFile API, so only on Java 25+. On an older JVM the jar is never loaded; REST responses
    // take Tier 0 and the GraphQL and request paths the reflective converter, same output.
    runtimeOnly(project(":protobuf-java-extras-classfile"))

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.datafaker)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}