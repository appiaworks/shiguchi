rootProject.name = "shiguchi"

include(
    "shiguchi-bom",
    "shiguchi-proto",
    "protobuf-java-extras",
    "protobuf-java-extras-classfile",
    "protoc-gen-java-extras",
    "protoc-gen-java-extras-gradle-plugin",
    "shiguchi-core",
    "shiguchi-rest",
    "shiguchi-graphql",
    "shiguchi-openapi",
    "shiguchi-avro",
    "shiguchi-grpc-proxy",
    "shiguchi-validation",
    "shiguchi-cursus",
    "shiguchi-cursus-cli",
    "shiguchi-bff",
    "shiguchi-benchmarks",
    "shiguchi-ui",
    "shiguchi-backend-support",
    "shiguchi-backend-support-jpa",
    // Spring Boot 4 modules
    "shiguchi-spring-boot4-autoconfigure",
    "shiguchi-spring-boot4-starter",
    "shiguchi-ui-spring-boot4-autoconfigure",
    "shiguchi-ui-spring-boot4-starter",
    "shiguchi-ui-webmvc-spring-boot4-starter",
    "shiguchi-graphql-spring-boot4-starter",
    "shiguchi-openapi-spring-boot4-starter",
    "shiguchi-avro-spring-boot4-starter",
    "shiguchi-validation-spring-boot4-autoconfigure",
    "shiguchi-validation-spring-boot4-starter",
    "shiguchi-cursus-spring-boot4-starter",
    "shiguchi-bff-spring-boot4-starter"
)

// Remap modules to their grouped subdirectory.
// Project names (and therefore artifact IDs) are unchanged.
project(":shiguchi-cursus").projectDir                          = file("shiguchi-cursus/shiguchi-cursus")
project(":shiguchi-cursus-cli").projectDir                      = file("shiguchi-cursus/shiguchi-cursus-cli")

// Remap Spring Boot 4 modules to their grouped subdirectory.
project(":shiguchi-spring-boot4-autoconfigure").projectDir    = file("spring-boot4/shiguchi-autoconfigure")
project(":shiguchi-spring-boot4-starter").projectDir          = file("spring-boot4/shiguchi-starter")
project(":shiguchi-ui-spring-boot4-autoconfigure").projectDir         = file("spring-boot4/shiguchi-ui-autoconfigure")
project(":shiguchi-ui-spring-boot4-starter").projectDir               = file("spring-boot4/shiguchi-ui-starter")
project(":shiguchi-ui-webmvc-spring-boot4-starter").projectDir          = file("spring-boot4/shiguchi-ui-webmvc-starter")
project(":shiguchi-graphql-spring-boot4-starter").projectDir          = file("spring-boot4/shiguchi-graphql-starter")
project(":shiguchi-openapi-spring-boot4-starter").projectDir          = file("spring-boot4/shiguchi-openapi-starter")
project(":shiguchi-avro-spring-boot4-starter").projectDir             = file("spring-boot4/shiguchi-avro-starter")
project(":shiguchi-validation-spring-boot4-autoconfigure").projectDir = file("spring-boot4/shiguchi-validation-autoconfigure")
project(":shiguchi-validation-spring-boot4-starter").projectDir       = file("spring-boot4/shiguchi-validation-starter")
project(":shiguchi-cursus-spring-boot4-starter").projectDir             = file("spring-boot4/shiguchi-cursus-starter")
project(":shiguchi-bff-spring-boot4-starter").projectDir              = file("spring-boot4/shiguchi-bff-starter")
