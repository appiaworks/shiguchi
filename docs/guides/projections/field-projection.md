# Field projection

Part of the [projections guide](index.md).

## Field projection (FieldMask pushdown)

Any method whose request message has a `read_mask` field accepts a projection. Both HTTP
surfaces populate it, and the backend sees the same `google.protobuf.FieldMask` either way.

### REST

Pass a comma-separated field list as `?fields=`, or as the `X-Fields` header when a
generated client can't easily add query parameters:

```bash
curl 'http://localhost:8080/v1/books?fields=id,title'
curl -H 'X-Fields: id,title' 'http://localhost:8080/v1/books'
```

Both become `read_mask: { paths: ["id", "title"] }` on the outbound request. If both are
present the query parameter wins.

Accepted spellings, normalised to proto snake_case before the mask is built:

| Input | Mask path |
| --- | --- |
| `?fields=title` | `title` |
| `?fields=publishedYear` | `published_year` |
| `?fields=published_year` | `published_year` |
| `?fields=author.displayName` | `author.display_name` |

Surrounding whitespace is tolerated, duplicates are collapsed, and malformed entries
(`a..b`, a trailing dot) are dropped rather than forwarded. When the request message has no
`read_mask` field the parameter is ignored entirely — it is never passed through as an
ordinary request field.

This is **request-side pushdown only**: the gateway tells the backend to compute less, and
serialises whatever comes back. It deliberately does not prune the response, so REST keeps
the fast Tier 0/1 serde paths (see [transcoding.md](../../concepts/transcoding.md)). A backend that
ignores `read_mask` therefore still returns full messages — the mask is a request, not a
gateway-enforced filter.

Methods with a `read_mask` field advertise `fields` as a documented query parameter in the
generated OpenAPI document, alongside `pageSize`/`pageToken`/`sort` where those apply, so
the projection is discoverable in Swagger UI.

### GraphQL

Projection has two halves on the GraphQL path, and the selection set drives both.

**1. Pushdown to the backend.** When a GraphQL query resolves a method whose request has a
`read_mask` field, `GraphQlFieldMaskInjector` turns the query's immediate selection set into
a `google.protobuf.FieldMask` and injects it, so the backend can compute less:

```graphql
query {
  listBooks(first: 25) {
    edges { node { id title } }
  }
}
```

sends `read_mask: { paths: ["id", "title"] }` on the request. Paths are converted from the
GraphQL camelCase names to **proto snake_case** (`publishedYear` → `published_year`).
Selections named `page*` / `sort*` that don't correspond to a real request field are
filtered out, so paging arguments never leak into the mask.

**2. Response conversion pruning.** The same selection set builds a `ProjectionNode`
(`shiguchi-core/src/main/java/dev/appiaworks/protobuf/extras/serde/ProjectionNode.java`) that limits
what the gateway bothers to convert out of the response:

- Scalar fields and repeated scalars **always** convert — they are cheap, and type
  resolvers or federation batch keys may read fields the query didn't select.
- Nested messages, repeated messages, and map fields convert **only when selected**.

Disable the response-side pruning with:

```yaml
shiguchi:
  graphql:
    selection-projection: false   # default: true
```

What it is worth, measured (`GraphQlProjectionBenchmark` in shiguchi-benchmarks): queries
that leave the large composites out convert 4× faster on a 3-item response and 25× on a 30-item
one; a query that selects the large list costs the same as converting it in full. Both paths
are compiled (Tier 2), so there is no case in which projection is slower — leave it on.

## OpenAPI / Swagger UI

The generated OpenAPI 3 document advertises each convention parameter on the operations
that actually support it — an operation gets a `fields` parameter only if its request
message has `read_mask`, `pageSize`/`pageToken` only if it has `page_request`, and `sort`
only if it has `sort_fields`:

| Parameter | Emitted when | Description in the spec |
| --- | --- | --- |
| `pageSize` (integer) | request has `page_request` | Maximum number of items to return |
| `pageToken` (string) | request has `page_request` | Opaque cursor from `X-Next-Page-Token` |
| `sort` (string) | request has `sort_fields` | Comma-separated `field[:asc\|desc]` |
| `fields` (string) | request has `read_mask` | Comma-separated projection, plus the selectable field names |

The `fields` parameter's description lists the top-level field names a projection can
name, taken from the repeated items message of the response (falling back to the response's
own fields), and carries an example value — so Swagger UI shows valid choices instead of a
bare text box.

The convention fields themselves are **not** advertised under their raw proto names. A
`page_request` message field is never emitted as a `pageRequest` query parameter, because
the gateway would not accept one; only the `pageSize` / `pageToken` spellings work.

### Current limitations

These are real gaps in the current release, not omissions in this document:

- **REST projection does not prune the response.** `?fields=` is pushed to the backend but
  the gateway serialises the full response it receives, to keep the Tier 0/1 fast paths.
  Only the GraphQL path prunes conversion. A backend that ignores `read_mask` sees no
  payload reduction on REST.
- **The mask field name is fixed** to `read_mask`. `field_mask` is not recognised, and
  there is no property to rename it.
- **Backward pagination is only half modelled.** `hasPreviousPage` and `startCursor` are
  answered (from `page_info.prev_page_token`, or the `after` cursor the caller arrived on — see
  [GraphQL: Relay Connections](paging.md#graphql-relay-connections)), but there is no `last` / `before`
  argument: a client can tell that a previous page exists without being able to ask for it.
- **Sort keys are not validated against the resource.** The gateway forwards whatever
  `field` string it is given; rejecting unknown keys is the backend's job (see the
  allow-list in the [JPA example](jpa-example.md); [`SortSpec`](../../reference/backend-support.md#sortspec) does it).

---
