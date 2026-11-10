# Paging

Part of the [projections guide](index.md).

## Paging

### Request side

For a method whose request has a `page_request` field, both surfaces map their paging
arguments onto it. REST reads query parameters; GraphQL reads the SDL arguments the schema
builder emits. Both run through the same `ConventionArgMapper`, so a backend sees one
request shape either way:

| REST query param | GraphQL argument | Mapped to |
| --- | --- | --- |
| `?pageSize=25` | — | `page_request.page_size` |
| `?first=25` | `first: 25` | `page_request.page_size` |
| `?pageToken=abc` | — | `page_request.page_token` |
| `?after=<cursor>` | `after: "<cursor>"` | `page_request.page_token` (base64-decoded, portion before `:`) |

`first` is the Relay spelling of `pageSize`; `after` is the Relay cursor. When both a
cursor and an explicit token are supplied, the cursor wins. A cursor that isn't valid
base64 is used as a raw token rather than rejected.

If the request message has **no** `page_request` field, these arguments are dropped rather
than passed through as unknown fields.

```bash
curl 'http://localhost:8080/v1/books?pageSize=25'
curl 'http://localhost:8080/v1/books?pageSize=25&pageToken=eyJvIjoyNX0'
```

```graphql
query {
  listBooks(first: 25, after: "dG9rMTIzOjU=") {
    edges { node { id title } }
    pageInfo { endCursor hasNextPage }
  }
}
```

### Pagination response headers

When the response message has a `page_info` field, the gateway reads it off the decoded
message and sets headers
(`shiguchi-rest/src/main/java/dev/appiaworks/shiguchi/rest/GrpcResponsePaginationExtractor.java`):

```
X-Next-Page-Token: eyJvIjoyNX0     # from page_info.next_page_token, omitted when blank
X-Total-Count: 1284                # from page_info.total_count, omitted when < 0 / unset
```

Header names are configurable:

```yaml
shiguchi:
  rest:
    pagination:
      next-page-token-header: X-Next-Page-Token
      total-count-header: X-Total-Count
```

Header extraction is **best-effort** — a malformed `page_info` is swallowed rather than
failing the request. Note the serde consequence documented in
[transcoding.md](../../concepts/transcoding.md): responses carrying `page_info` need a decoded message, so
they use Tier 1/2 and never the Tier 0 wire-to-JSON fast path.

### GraphQL: Relay Connections

A response shaped `{repeated items, page_info}` is exposed as a Relay Connection —
`edges { node }`, `pageInfo`, `totalCount` — by `RelayConnectionTransformer`. `endCursor`
and `hasNextPage` are synthesised from `next_page_token`; edge cursors are
`base64(nextPageToken + ":" + index)`.

`startCursor` and `hasPreviousPage` come from whichever of these the gateway can know:

| Source | `hasPreviousPage` | `startCursor` |
| --- | --- | --- |
| `page_info.prev_page_token` set | `true` | that token |
| the caller sent `after` | `true` | the cursor they sent |
| neither | `false` | `null` |

`prev_page_token` is optional and the gateway ships no message for it to live in — like every
other field here it is matched by name. Set it when your backend can cheaply produce a cursor
back to the current page's start; a client can then page in both directions. Without it the
gateway still answers honestly, because a caller that arrived on an `after` cursor is by
definition past the first page.

> Before this, `startCursor` was always `null` and `hasPreviousPage` always `false` — the schema
> advertised both fields and then denied there was anything behind, no matter how far the caller
> had paged.

---
