plugins {
    id("java-platform")
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api("io.github.appiaworks:shiguchi-proto:${project.version}")
        api("io.github.appiaworks:protobuf-java-extras:${project.version}")
        api("io.github.appiaworks:protobuf-java-extras-classfile:${project.version}")
        api("io.github.appiaworks:protoc-gen-java-extras:${project.version}")
        api("io.github.appiaworks:shiguchi-core:${project.version}")
        api("io.github.appiaworks:shiguchi-rest:${project.version}")
        api("io.github.appiaworks:shiguchi-graphql:${project.version}")
        api("io.github.appiaworks:shiguchi-openapi:${project.version}")
        api("io.github.appiaworks:shiguchi-avro:${project.version}")
        api("io.github.appiaworks:shiguchi-grpc-proxy:${project.version}")
        api("io.github.appiaworks:shiguchi-ui:${project.version}")
        api("io.github.appiaworks:shiguchi-spring-boot3-autoconfigure:${project.version}")
        api("io.github.appiaworks:shiguchi-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-ui-spring-boot3-autoconfigure:${project.version}")
        api("io.github.appiaworks:shiguchi-ui-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-spring-boot4-autoconfigure:${project.version}")
        api("io.github.appiaworks:shiguchi-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-ui-spring-boot4-autoconfigure:${project.version}")
        api("io.github.appiaworks:shiguchi-ui-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-graphql-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-openapi-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-graphql-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-openapi-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-avro-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-avro-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-cursus:${project.version}")
        api("io.github.appiaworks:shiguchi-cursus-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-cursus-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-bff:${project.version}")
        api("io.github.appiaworks:shiguchi-bff-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-bff-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-validation:${project.version}")
        api("io.github.appiaworks:shiguchi-validation-spring-boot3-autoconfigure:${project.version}")
        api("io.github.appiaworks:shiguchi-validation-spring-boot3-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-validation-spring-boot4-autoconfigure:${project.version}")
        api("io.github.appiaworks:shiguchi-validation-spring-boot4-starter:${project.version}")
        api("io.github.appiaworks:shiguchi-cursus-cli:${project.version}")
    }
}