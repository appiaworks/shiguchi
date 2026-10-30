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
    // Spring Boot 3 modules
    "shiguchi-spring-boot3-autoconfigure",
    "shiguchi-spring-boot3-starter",
    "shiguchi-ui-spring-boot3-autoconfigure",
    "shiguchi-ui-spring-boot3-starter",
    "shiguchi-ui-webmvc-spring-boot3-starter",
    "shiguchi-graphql-spring-boot3-starter",
    "shiguchi-openapi-spring-boot3-starter",
    "shiguchi-avro-spring-boot3-starter",
    "shiguchi-validation-spring-boot3-autoconfigure",
    "shiguchi-validation-spring-boot3-starter",
    "shiguchi-cursus-spring-boot3-starter",
    "shiguchi-bff-spring-boot3-starter",
    // Spring Boot 4 modules
    "shiguchi-spring-boot4-autoconfigure",
    "shiguchi-benchmarks-boot4",
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

// Remap Spring Boot 3 modules to their grouped subdirectory.
// Project names (and therefore artifact IDs) are unchanged.
project(":shiguchi-benchmarks-boot4").projectDir              = file("spring-boot4/shiguchi-benchmarks")
project(":shiguchi-spring-boot3-autoconfigure").projectDir    = file("spring-boot3/shiguchi-autoconfigure")
project(":shiguchi-spring-boot3-starter").projectDir          = file("spring-boot3/shiguchi-starter")
project(":shiguchi-ui-spring-boot3-autoconfigure").projectDir         = file("spring-boot3/shiguchi-ui-autoconfigure")
project(":shiguchi-ui-spring-boot3-starter").projectDir               = file("spring-boot3/shiguchi-ui-starter")
project(":shiguchi-ui-webmvc-spring-boot3-starter").projectDir          = file("spring-boot3/shiguchi-ui-webmvc-starter")
project(":shiguchi-graphql-spring-boot3-starter").projectDir          = file("spring-boot3/shiguchi-graphql-starter")
project(":shiguchi-openapi-spring-boot3-starter").projectDir          = file("spring-boot3/shiguchi-openapi-starter")
project(":shiguchi-avro-spring-boot3-starter").projectDir             = file("spring-boot3/shiguchi-avro-starter")
project(":shiguchi-validation-spring-boot3-autoconfigure").projectDir = file("spring-boot3/shiguchi-validation-autoconfigure")
project(":shiguchi-validation-spring-boot3-starter").projectDir       = file("spring-boot3/shiguchi-validation-starter")
project(":shiguchi-cursus-spring-boot3-starter").projectDir             = file("spring-boot3/shiguchi-cursus-starter")
project(":shiguchi-bff-spring-boot3-starter").projectDir              = file("spring-boot3/shiguchi-bff-starter")
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