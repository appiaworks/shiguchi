# Third-party components

Shiguchi is licensed under the [Apache License 2.0](LICENSE). It stands on the
open source projects listed here — each remains under its own license, reproduced by its
own distribution.

Versions are the ones pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
and [`shiguchi-ui-frontend/package.json`](shiguchi-ui-frontend/package.json) in the current release.
Starter artifacts resolve Spring Boot, springdoc, and DGS versions per line — see the
compatibility matrix in [README.md](README.md#compatibility).

## Runtime dependencies (JVM)

| Component | Version | License | Project |
| --- | --- | --- | --- |
| gRPC-Java (`grpc-services`, `grpc-stub`, `grpc-netty`, `grpc-protobuf`, `grpc-inprocess`) | 1.82.1 | Apache-2.0 | https://github.com/grpc/grpc-java |
| gRPC OpenTelemetry (`grpc-opentelemetry`) | 1.82.1 | Apache-2.0 | https://github.com/grpc/grpc-java |
| Protocol Buffers Java (`protobuf-java`, `protobuf-java-util`) | 4.35.1 | BSD-3-Clause | https://github.com/protocolbuffers/protobuf |
| Google Common Protos (`proto-google-common-protos`) | 2.73.0 | Apache-2.0 | https://github.com/googleapis/googleapis |
| Spring Boot | 3.5.x / 4.0.x | Apache-2.0 | https://github.com/spring-projects/spring-boot |
| Spring Cloud Gateway | 4.3.x / 5.0.x | Apache-2.0 | https://github.com/spring-cloud/spring-cloud-gateway |
| Spring Security / OAuth2 Resource Server | via Boot BOM | Apache-2.0 | https://github.com/spring-projects/spring-security |
| Project Reactor | via Boot BOM | Apache-2.0 | https://github.com/reactor/reactor-core |
| Netflix DGS Framework | 10.6.x / 12.x | Apache-2.0 | https://github.com/Netflix/dgs-framework |
| GraphQL Java | via DGS BOM | MIT | https://github.com/graphql-java/graphql-java |
| springdoc-openapi | 2.9.x / 3.0.x | Apache-2.0 | https://github.com/springdoc/springdoc-openapi |
| Swagger UI (bundled by springdoc) | via springdoc | Apache-2.0 | https://github.com/swagger-api/swagger-ui |
| Caffeine | 3.2.4 | Apache-2.0 | https://github.com/ben-manes/caffeine |
| Apache Avro (`avro`, `shiguchi-avro` only) | 1.12.2 | Apache-2.0 | https://github.com/apache/avro |
| Jackson | via Boot BOM | Apache-2.0 | https://github.com/FasterXML/jackson |
| protovalidate-java | 1.2.2 | Apache-2.0 | https://github.com/bufbuild/protovalidate-java |
| ClassGraph | 4.8.184 | MIT | https://github.com/classgraph/classgraph |
| Guava | via gRPC/protobuf | Apache-2.0 | https://github.com/google/guava |
| Micrometer / Micrometer Tracing | via Boot BOM | Apache-2.0 | https://github.com/micrometer-metrics/micrometer |
| OpenTelemetry Java | via Boot BOM | Apache-2.0 | https://github.com/open-telemetry/opentelemetry-java |
| Netty | via gRPC / Reactor | Apache-2.0 | https://github.com/netty/netty |
| SLF4J | via Boot BOM | MIT | https://www.slf4j.org |

## Build-time only

| Component | Version | License | Project |
| --- | --- | --- | --- |
| Gradle | 9.5.1 | Apache-2.0 | https://github.com/gradle/gradle |
| Protobuf Gradle Plugin | 0.10.0 | BSD-3-Clause | https://github.com/google/protobuf-gradle-plugin |
| Lombok | 1.18.46 | MIT | https://github.com/projectlombok/lombok |
| Spotless + palantir-java-format | 8.9.0 | Apache-2.0 | https://github.com/diffplug/spotless |
| JUnit 5 | 5.11.3 | EPL-2.0 | https://github.com/junit-team/junit5 |
| Mockito | 5.14.2 | MIT | https://github.com/mockito/mockito |
| Datafaker | 2.7.0 | Apache-2.0 | https://github.com/datafaker-net/datafaker |
| JMH | via benchmarks module | GPL-2.0-with-classpath-exception | https://github.com/openjdk/jmh |

JMH is used only by the `shiguchi-benchmarks` module, which is not published and not
part of any distributed artifact.

## Bundled web console assets (`grpc-ui`)

The `grpc-ui` module ships pre-built static assets compiled from `shiguchi-ui-frontend`. Those
bundles embed the following, all MIT-licensed:

| Component | Version | License | Project |
| --- | --- | --- | --- |
| React / React DOM | 19.2.x | MIT | https://github.com/facebook/react |
| Monaco Editor (`@monaco-editor/react`) | 4.6.x | MIT | https://github.com/microsoft/monaco-editor |
| Zustand | 5.0.x | MIT | https://github.com/pmndrs/zustand |
| react-resizable-panels | 4.11.x | MIT | https://github.com/bvaughn/react-resizable-panels |
| Tailwind CSS | 4.3.x | MIT | https://github.com/tailwindlabs/tailwindcss |
| Vite / TypeScript / PostCSS / Autoprefixer | build-time | MIT | https://vitejs.dev, https://www.typescriptlang.org |

## Reporting an attribution problem

If a component is missing, misattributed, or its license is stated incorrectly, please open
an issue — attribution fixes are treated as bugs.
