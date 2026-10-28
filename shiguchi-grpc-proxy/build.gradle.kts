dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shiguchi-core"))
    api(libs.grpc.netty)

    implementation(libs.spring.boot.autoconfigure)
    implementation("org.slf4j:slf4j-api")
    implementation("com.google.guava:guava:33.7.2-jre")


    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}