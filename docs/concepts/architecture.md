# Architecture

What sits between your clients and your gRPC services.

```mermaid
flowchart TB
    subgraph clients [Clients]
        R[REST / JSON]
        G[GraphQL]
        O[OpenAPI / Swagger UI]
        U[gRPC Web Console]
        P[gRPC clients]
        S[Streaming clients<br/>NDJSON · SSE · WebSocket · gRPC-Web]
        W[Browsers<br/>BFF session cookie]
    end

    subgraph gateway [Shiguchi]
        route[Route registry<br/>google.api.http + gql annotations]
        serde[Tiered dynamic serde<br/>wire / proto ⇄ JSON / Map]
        disp[Reactive dispatcher<br/>non-blocking, flow-controlled]
        proxy[Transparent gRPC proxy<br/>native gRPC port + merged reflection]
        auth[Auth edge<br/>JWT · BFF sign-in · authorizer SPI]
        push[Cursus push<br/>topics · fan-out · replay]
        disco[Schema discovery<br/>reflection · classpath · .pb file · OpenAPI]
    end

    subgraph downstream [Downstream services]
        B[(gRPC backends)]
        A[(REST backends<br/>Avro payloads)]
        H[(REST backends<br/>JSON payloads)]
    end

    R --> route
    G --> route
    O --> route
    U --> route
    P --> proxy
    S --> route
    W -.-> auth
    auth -.-> route
    G -. subscriptions · @live .-> push
    push --> S
    route --> serde
    serde --> disp
    disp --> B
    disp --> A
    disp --> H
    proxy --> B
    route -.-> disco
    disco -.descriptors.-> serde
```

Streaming RPCs (server, client and bidirectional) reach REST callers as NDJSON, SSE or WebSocket frames, GraphQL callers as subscriptions or `@live` queries, and browsers as gRPC-Web.

## Request flow

```mermaid
sequenceDiagram
    participant C as Client
    participant G as Shiguchi
    participant B as gRPC backend
    C->>G: GET /v1/books?pageSize=2
    G->>G: auth edge, route lookup (google.api.http)
    G->>G: JSON/query to protobuf (serde tier)
    G->>B: ListBooks (gRPC, non-blocking)
    B-->>G: ListBooksResponse
    G->>G: protobuf to JSON
    G-->>C: 200 application/json
```

How a request moves through the serde tiers: [Transcoding](transcoding.md).

The README shows the same diagrams as SVG with light and dark variants; the sources are `docs/images/architecture*.svg` and `request-flow*.svg`.
