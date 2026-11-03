dependencies {
    implementation(platform(libs.spring.boot.bom))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    // DGS is Boot-line specific (10.x = Boot 3/Spring 6, 12.x = Boot 4/Spring 7).
    // compileOnly against the DGS 12 API (the used surface is identical in 10.x) —
    // the per-line graphql starters supply the matching runtime version, so this shared
    // module's POM never drags one Boot line into the other.
    compileOnly(platform(libs.dgs.platform))
    // DGS 12.1.0's platform pins the Boot 4.0.0 generation (spring-boot, tools.jackson 3.0.2,
    // spring-graphql 2.0.0), several with CVEs. The Boot 4 BOM raises them to the release the
    // Boot 4 line ships with. compileOnly and test only: the published POM is unchanged.
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

    // DGS 12 pulls Spring 7 transitively which breaks WebClient (compiled against Spring 6).
    // Force all spring-framework artifacts back to the Boot 3 / Spring 6 version for tests. Compile
    // too: compiled against Spring 7, a test binds to HttpHeaders.get(String), absent from 6.2.
    configurations.matching { it.name in setOf("testCompileClasspath", "testRuntimeClasspath") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.springframework" && requested.name.startsWith("spring-")) {
                useVersion("6.2.19")
                because("DGS 12 requires Spring 7 but production code uses Spring 6 WebClient")
            }
        }
    }
}