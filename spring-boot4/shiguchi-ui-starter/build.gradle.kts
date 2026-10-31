dependencies {
    api(platform(libs.spring.boot4.bom))
    api(project(":shiguchi-ui-spring-boot4-autoconfigure"))
    api(project(":shiguchi-ui"))
    api(project(":shiguchi-core"))
    api(project(":shiguchi-rest"))
    api(libs.spring.boot.starter.webflux)
}