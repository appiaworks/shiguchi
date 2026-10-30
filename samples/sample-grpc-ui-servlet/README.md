# sample-grpc-ui-servlet

The gRPC web console embedded in a **Spring MVC (servlet)** application, through
`shiguchi-ui-webmvc-spring-boot3-starter`. The application keeps its own controller
(`GET /hello`); the console mounts at `/grpc-ui` next to it. No WebFlux or Netty on the
classpath.

Point `shiguchi.services` in `application.yml` at a gRPC server with reflection enabled
(`localhost:9090` by default), then:

```bash
./gradlew -p samples sample-grpc-ui-servlet:bootRun
```

Open http://localhost:8080/grpc-ui.

Servlet mode serves the console only: what it leaves out compared with the WebFlux starter is
listed in [docs/ui.md](../../docs/ui.md#servlet-spring-mvc-applications). Security is the
host's: `SecurityConfig` opens everything for a local demo and keeps CSRF on, with the token
in the `XSRF-TOKEN` cookie the console sends back as `X-XSRF-TOKEN`. Authenticate `/grpc-ui/**`
before exposing it anywhere.

The WebMVC starter first ships in 0.15.0. Until then, publish locally
(`./gradlew publishToMavenLocal`) and pass `-PgatewayVersion=<that version>`.
