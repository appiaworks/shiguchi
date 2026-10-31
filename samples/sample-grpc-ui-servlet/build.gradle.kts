plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"

val gatewayVersion = providers.gradleProperty("gatewayVersion").get()

dependencies {
    // A Spring MVC (servlet) application that embeds the gRPC web console next to its own
    // controllers. The WebMVC starter brings spring-boot-starter-web, not WebFlux.
    implementation("io.github.appiaworks:shiguchi-ui-webmvc-spring-boot3-starter:$gatewayVersion")
}
