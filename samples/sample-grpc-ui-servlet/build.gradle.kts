plugins {
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "dev.appiaworks.shiguchi.sample"
version = "0.14.1"

// The WebMVC UI starter first ships in 0.15.0: until then, build against a local
// publishToMavenLocal with -PgatewayVersion=<that version>.
val gatewayVersion = providers.gradleProperty("gatewayVersion").getOrElse("0.14.1")

dependencies {
    // A Spring MVC (servlet) application that embeds the gRPC web console next to its own
    // controllers. The WebMVC starter brings spring-boot-starter-web, not WebFlux.
    implementation("io.github.appiaworks:shiguchi-ui-webmvc-spring-boot3-starter:$gatewayVersion")
}
