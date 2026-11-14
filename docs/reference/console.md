# Console reference

Reference for the embedded gRPC web console: the routes it serves, the JSON it exchanges with the gateway, and its
configuration. Derived from `shiguchi-ui` (`GrpcUiConfig`, `GrpcUiController`, `GrpcUiService`, `GrpcUiSchemaDto`) and
its servlet counterpart (`dev.appiaworks.shiguchi.ui.servlet`). For a tour of the screens see the
[console guide](../guides/ui.md). Configuration keys are also listed in [configuration.md](configuration.md).

## 1. Where it is served

| Path | Method | Purpose |
| --- | --- | --- |
| `{path}` and `{path}/` | GET | Redirect (302, context-path aware) to `{path}/index.html` |
| `{path}/**` | GET | The bundled single-page app, from `classpath:/static/grpc-ui/` |
| `{path}/api/config` | GET | Links to the sibling surfaces and the configured auth providers |
| `{path}/api/schema` | GET | Every discovered service, method, and its input and output schema |
| `{path}/api/invoke` | POST | Unary or client-streaming call |
| `{path}/api/invoke/stream` | POST | Server-streaming call, as server-sent events |
| `{path}/api/auth/{scheme}/token` | POST | Server-side OAuth2 `client_credentials` exchange |

- `{path}` is `shiguchi.ui.path` (default `/grpc-ui`). The routes exist only when `shiguchi.ui.enabled=true`.
- The same routes are served by the WebFlux starter and by the WebMVC (servlet) starter. The page and the API resolve
  relative to the page, so a `server.servlet.context-path` or `spring.webflux.base-path` works.
- Navigation inside the app is hash-based (`/#/...`), so no server-side fallback route is needed.
- Security surface `ui` covers the console and GraphiQL (`shiguchi.security.surfaces.ui.require-auth`, or the alias
  `shiguchi.ui.require-auth`). In servlet mode the host application's `SecurityFilterChain` protects `{path}/**`
  and `shiguchi.ui.require-auth` is not applied.

## 2. `GET {path}/api/config`

Returns the links the header shows and the auth providers the **Auth** dialog offers.

| Field | Meaning |
| --- | --- |
| `swaggerEnabled`, `swaggerPath` | `shiguchi.openapi.enabled`, and `springdoc.swagger-ui.path` (default `/swagger-ui.html`). `swaggerEnabled` is true only when springdoc is on the classpath and `springdoc.swagger-ui.enabled` is not `false`, so the header link shows only when Swagger UI is served |
| `graphqlEnabled`, `graphqlPath` | `shiguchi.graphql.enabled`, and `dgs.graphql.graphiql.path` (default `/graphiql`). `graphqlEnabled` is true only when DGS (which serves GraphiQL) is on the classpath and `dgs.graphql.graphiql.enabled` is not `false`. `/graphiql` is the console's own page (`graphiql.html` under the UI path), with an Authorize panel |
| `grpcProxyEnabled`, `grpcProxyPort` | `shiguchi.grpc.enabled` and the proxy port, shown in the header |
| `authProviders` | One entry per `shiguchi.openapi.security-schemes.*` (below) |
| `graphqlEndpoint` | The GraphQL HTTP endpoint GraphiQL posts to: `spring.graphql.http.path`, then `spring.graphql.path`, then `dgs.graphql.path`, default `/graphql` |
| `graphqlWebSocketPath` | `spring.graphql.websocket.path`, or an empty string when no WebSocket is configured (GraphiQL then has no subscriptions) |
| `version` | The Shiguchi version, read from the console jar's manifest; empty when run from classes. Shown next to the title in the top bar |
| `pushEnabled` | `shiguchi.cursus.enabled`; shows the **Push** button |
| `pushPath` | `shiguchi.cursus.path` (default `/push`), for the live tail's WebSocket |
| `pushAdminPath` | The `gateway-push` actuator endpoint (`management.endpoints.web.base-path` + `/gateway-push`) |

Every path in the response is absolute and already carries the request's context path.

An `authProviders` entry has `id` (the scheme key), `type` (`http`, `oauth2`, `apiKey` or `openIdConnect`),
`scheme` (`bearer` or `basic` for `http`), `description`, `apiKeyIn` and `apiKeyName` (for `apiKey`), and the
`authorizationCode` and `clientCredentials` flows. A flow carries `authorizationUrl`, `tokenUrl`, `scopes` and
`clientId`. `clientId` is public and pre-fills the dialog; the client secret is never part of the configuration.

## 3. `GET {path}/api/schema`

A list of services. Each has `name` (fully qualified), `target`, `namespace` (the proto package), `configName`
(the `shiguchi.services[].name`) and `methods`.

A method has `name`, `fullServiceName`, `methodType` (`UNARY`, `SERVER_STREAMING`, `CLIENT_STREAMING` or
`BIDI_STREAMING`), `inputSchema`, `outputTypeName` and, when the proto carries a `google.api.http` rule,
`httpMethod` and `httpPath`.

`inputSchema` is a message (`fullName`, `fields`). A field has `name`, `jsonName`, `type` (lowercase proto type:
`string`, `int32`, `message`, `enum`, `bytes`, `bool` ...), `label` (`optional`, `repeated` or `map`), `typeName`
(fully qualified, for message and enum fields), nested `fields` (inlined up to a depth limit), `enumValues`
(`name`, `number`), `oneofName`, `mapEntry`, `wellKnownType` (for example `TIMESTAMP`, `DURATION`, `FIELD_MASK`,
`STRUCT`, `ANY`) and `hasPresence` (message, oneof member or proto3 `optional`: unset differs from the default).

Method visibility follows `services[].include-methods` / `exclude-methods`, and the schema works with every
discovery mode (`DYNAMIC`, `STATIC`, `PB_FILE`).

## 4. `POST {path}/api/invoke`

Request body:

| Field | Meaning |
| --- | --- |
| `serviceName` | Fully qualified proto service, for example `demo.library.LibraryService` |
| `methodName` | Method name |
| `payload` | The request message as a proto3 JSON string. Null or blank is `{}` |
| `payloads` | Client-streaming only: every request message, each a JSON string. When absent, `payload` is the one message |
| `metadata` | `[{ "key": ..., "value": ... }]`, extra gRPC metadata merged on top of the standard call metadata |

The response is **always HTTP 200**; a failed call is reported in the body:

| Field | Meaning |
| --- | --- |
| `response` | The response message as proto3 JSON; null on error |
| `grpcStatus` | gRPC status code name: `OK`, `NOT_FOUND`, `PERMISSION_DENIED` ... |
| `errorMessage` | The status description; null on success |
| `errorDetails` | The `google.rpc.Status` details the backend attached, each as proto3 JSON with its `@type`; null when there are none |
| `durationMs` | Wall-clock time of the call |

Two statuses come from the console rather than the backend: `NOT_FOUND` when the service is unknown or is a
`REST_DOWNSTREAM` service (synthetic descriptors, no gRPC channel), and `INTERNAL` for any other failure while
dispatching.

The call metadata is built the same way as for a transcoded request: the principal (`x-principal`, `anonymous`
when there is none), the `Authorization` header, the tracing headers and `shiguchi.client.forward-headers`. Entries
in `metadata` are added after those.

## 5. `POST {path}/api/invoke/stream`

Same request body; used for server-streaming methods. The response is `text/event-stream`; the browser reads it with
`fetch` (not `EventSource`, which only supports GET). Events:

| Event | Data |
| --- | --- |
| `data` | One response message as JSON, per chunk |
| `error` | `{ "code": ..., "message": ..., "details": ... }`; the stream then ends with `done` |
| `done` | `{ "durationMs": ... }`, always last |

An unknown `serviceName` answers HTTP 404 with no body, before any event.

## 6. `POST {path}/api/auth/{scheme}/token`

Exchanges client credentials for a token on the server, so the browser never calls the provider's token endpoint
with the secret. Body: `{ "clientId", "clientSecret", "scopes": [...] }`.
The gateway posts `grant_type=client_credentials`, `client_id`, `client_secret` and the space-joined `scope`
to the `token-url` of the scheme's `client-credentials` flow, with a 30 s timeout.

| Outcome | Response |
| --- | --- |
| Success | `200` `{ "accessToken", "expiresIn", "tokenType" }` (`expiresIn` defaults to 3600, `tokenType` to `Bearer`) |
| Unknown scheme, or a scheme without a `client-credentials` flow | `404`, no body |
| Provider returned non-2xx, was unreachable, or answered an unreadable body | `400` `{ "error": "<message>" }` |

The `authorization-code` flow runs in the browser: the console opens `authorization-url` and receives the code on
its own callback page. The default Content-Security-Policy allows the console to reach the origin of each
configured `token-url` (see `shiguchi.headers.content-security-policy` in [configuration.md](configuration.md)).

## 7. Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `shiguchi.ui.enabled` | `false` | Mount the console |
| `shiguchi.ui.path` | `/grpc-ui` | Base path of the page and its API |
| `shiguchi.ui.require-auth` | `false` | Require an authenticated principal for the UI surface (alias of `shiguchi.security.surfaces.ui.require-auth`) |

The links and auth dialog also depend on keys owned by other pages: `shiguchi.openapi.security-schemes.*`
([OpenAPI reference](openapi.md)), `shiguchi.openapi.enabled`, `shiguchi.graphql.enabled`, `shiguchi.grpc.enabled`
and `shiguchi.cursus.enabled`.

## 8. Limits

- The console can invoke any wired method; it is disabled by default and should be gated (`require-auth`) outside a
  trusted network. See [SECURITY.md](../../SECURITY.md).
- Bidirectional-streaming methods are listed (badge `BD`) but the console does not invoke them: one HTTP request
  cannot carry an interleaved conversation. Use the gRPC proxy port.
- Servlet mode serves the console only: no REST transcoding, gRPC-Web, rate limiting, security headers filter,
  BFF relay or Cursus. See [Servlet applications](../guides/ui.md#servlet-spring-mvc-applications).
