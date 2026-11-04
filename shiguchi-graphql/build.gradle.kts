dependencies {
    implementation(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    // compileOnly against DGS: the graphql starter supplies the runtime version, so this shared
    // module's POM does not pin one.
    compileOnly(platform(libs.dgs.platform))
    // DGS 12.1.0's platform pins the Boot 4.0.0 generation (spring-boot, tools.jackson 3.0.2,
    // spring-graphql 2.0.0), several with CVEs. The Boot BOM raises them to the release the
    // starters ship with. compileOnly and test only: the published POM is unchanged.
    compileOnly(platform(libs.spring.boot4.bom))
    compileOnly(libs.dgs.spring.graphql)
    compileOnly(libs.dgs.extended.scalars)
    compileOnly(libs.dgs.micrometer)
    implementation(libs.spring.boot.starter.webflux)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    // The push module's GraphQL subscription fields live here, behind @ConditionalOnClass: present
    // only when an application has both starters, so neither module depends on the other at runtime.
    compileOnly(project(":shiguchi-cursus"))


    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform(libs.dgs.platform))
    testImplementation(platform(libs.spring.boot4.bom))
    testImplementation(libs.dgs.spring.graphql)
    testImplementation(libs.dgs.extended.scalars)
    testImplementation(libs.dgs.micrometer)
    testImplementation(libs.dgs.spring.graphql.test)
    testImplementation(libs.reactor.test)
    testImplementation(project(":shiguchi-cursus"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
