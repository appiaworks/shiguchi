# Quick start

Requirements: **JDK 21+**. The generated serde tiers (Tier 1 for compiled stubs, Tier 2 for
everything else) are built with the Class-File API and so switch on at **Java 25+** only; on
21–24 the gateway serves REST through the wire transcoder (Tier 0) and the GraphQL and request
paths through the reflective converter, with the same JSON. Stubs built with
[`protoc-gen-java-extras`](../libraries/protoc-java-extras.md#json) carry their Tier 1 writer and get it
on any Java. (Node 22+ only if rebuilding the UI frontend.)

### 1. Add a starter

> Releases are published to **Maven Central**: no extra repository configuration needed.

Gradle (Spring Boot 4):

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot4-starter:0.20.0")
    // optional surfaces:
    implementation("io.github.appiaworks:shiguchi-openapi-spring-boot4-starter:0.20.0")
    implementation("io.github.appiaworks:shiguchi-graphql-spring-boot4-starter:0.20.0")
    implementation("io.github.appiaworks:shiguchi-ui-spring-boot4-starter:0.20.0")
    implementation("io.github.appiaworks:shiguchi-avro-spring-boot4-starter:0.20.0")   // Avro to REST backends
}
```

Maven (Spring Boot 4):

```xml
<dependency>
  <groupId>io.github.appiaworks</groupId>
  <artifactId>shiguchi-spring-boot4-starter</artifactId>
  <version>0.20.0</version>
</dependency>
```

The starter brings the Boot-4-matched runtime line (Spring Cloud Gateway 5.0.x, springdoc 3.2.x,
DGS 12.x). With the GraphQL starter, also set `json-path.version` to `3.0.0`
([details](../operations/troubleshooting.md#noclassdeffounderror-comjaywayjsonpathspijsonjackson3jsonprovider)).

On Spring Boot 3, stay on 0.18.x, the last release line with the `spring-boot3` starters
([Compatibility](../operations/compatibility.md)).

To embed only the gRPC console in a servlet (Spring MVC) application, use
`shiguchi-ui-webmvc-spring-boot4-starter` instead of the WebFlux UI starter
([docs/ui.md](../guides/ui.md#servlet-spring-mvc-applications)).

### 2. Point it at your gRPC services

`application.yml`:

```yaml
shiguchi:
  services:
    - name: library-service
      host: localhost
      port: 9090
      mode: DYNAMIC        # DYNAMIC (server reflection) | STATIC (classpath) | PB_FILE
  openapi:  { enabled: true }
  graphql:  { enabled: true }
  ui:       { enabled: false }   # enable only behind auth
```

### 3. Annotate your protos (optional but recommended)

Standard `google.api.http` bindings drive the REST routes; `(shiguchi.graphql)` refines the
GraphQL side (without it, methods still map by convention):

```protobuf
service LibraryService {
  rpc GetBook(GetBookRequest) returns (Book) {
    option (google.api.http) = { get: "/v1/books/{id}" };
  }
  rpc ListBooks(ListBooksRequest) returns (ListBooksResponse) {
    option (google.api.http) = { get: "/v1/books" };
    option (shiguchi.graphql) = { name: "books", type: QUERY };
  }
  rpc CreateBook(CreateBookRequest) returns (Book) {
    option (google.api.http) = { post: "/v1/books", body: "book" };
    option (shiguchi.graphql) = { name: "createBook", type: MUTATION };
  }
}
```

### 4. Run and call

```bash
./gradlew :samples:sample-rest-gateway:bootRun
```

One backend, four surfaces:

```bash
# REST — transcoded from the http annotations
curl localhost:8080/v1/books/1
# {"id":"1","title":"The Pragmatic Programmer","author":"Hunt & Thomas","year":1999}

# GraphQL
curl -X POST localhost:8080/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ books { books { title author } } }"}'

# OpenAPI spec + Swagger UI          gRPC console (when enabled)
open localhost:8080/swagger-ui.html  # localhost:8080/grpc-ui
```

## Try the samples

Self-contained example apps under [`samples/`](../../samples):

| Sample | What it shows |
| --- | --- |
| `sample-rest-gateway` | Minimal REST/JSON gateway |
| `sample-graphql-gateway` | GraphQL + federation |
| `sample-grpc-proxy` | Pass-through gRPC proxy |
| `sample-grpc-ui-standalone` | Just the gRPC web console |
| `sample-grpc-ui-servlet` | The gRPC web console embedded in a Spring MVC (servlet) application |
| `sample-rest-downstream` | A REST (non-gRPC) backend aggregated via `REST_DOWNSTREAM` |
| `sample-avro-downstream` | The same, spoken to in Avro: `x-avro-schema` in the document, `upstream-content.preferred: avro/binary` |
| `sample-rate-limited-gateway` | Inbound rate limiting, in memory or shared through Redis (`--spring.profiles.active=redis`) |
| `sample-protobuf-java-extras` | No gateway: the proto JSON library and typed accessors in plain Java, plus a Kafka serde in proto binary or JSON |
| `sample-full` | All surfaces together |

```bash
./gradlew :samples:sample-full:bootRun
```

## Build from source

```bash
./gradlew build          # compile + test all modules
./gradlew :samples:sample-full:bootBuildImage   # OCI image via Spring buildpacks
```

Rebuild the UI frontend (output is bundled into `shiguchi-ui`):

```bash
cd shiguchi-ui-frontend && npm ci && npm run build
```

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for the full workflow.
