# Merging across multiple backends

One REST or GraphQL endpoint, served by fanning out to several gRPC backends that return the
same message type, merged into one sorted and paged result. Callers send the same `?sort=` and
`?pageSize=` they would send any list endpoint and cannot tell the result spans more than one
service.

Typical shapes:

- the same entity sharded across services (orders in `orders-eu` and `orders-us`)
- an old service and its replacement, both live during a migration
- a domain split by concern (`characters` and `character-refs`) that a client wants as one list

Any number of sources is supported — two and twenty run the same code. What grows with source
count is fan-out width, cursor size, and the odds that one backend fails.

## The one rule that matters

A merge is a **merge of sorted sequences**. The gateway holds one record from each source and
repeatedly emits the smallest — so it only ever compares the records currently at the front of
each stream.

If a source hands back records out of order, a record that should have sorted early can be sitting
further down that stream, where the merge has already moved past it. The result **looks** perfectly
sorted and is silently missing rows that belonged near the front. There is no error, and a bigger
buffer only makes the failure rarer, not absent.

So everything below comes down to one question per source: **does that backend sort its own
output?**

## Two modes, chosen per source

### Pre-sorted — push the sort down

The backend understands `sort_fields` and returns its rows already ordered.

The gateway forwards the caller's sort, asks for a bounded window, and streams the response —
holding one record per source. Memory is O(sources) no matter how large the result is, and the
merge stops as soon as the page is full.

```
caller: ?sort=name:asc&pageSize=25
  ├─ orders-eu  ← sort_fields=[name asc], page_size=75   (streamed)
  └─ orders-us  ← sort_fields=[name asc], page_size=75   (streamed)
```

This is what you want. It requires an `ORDER BY` in the backend and nothing else.

### Unsorted — materialise in the gateway

The backend ignores `sort_fields` and returns rows in whatever order it likes.

The gateway reads that source **in full**, sorts it in memory, and merges the result. Correct at
any size, but memory scales with that source's entire result set.

```
caller: ?sort=name:asc&pageSize=25
  └─ legacy-orders ← (no sort, no paging)  → all rows buffered, sorted in the gateway
```

Note what is *not* sent: no `sort_fields`, and **no page size**. That second one is the part that
surprises people.

> **Why an unsorted backend cannot be paged**
>
> The tempting middle ground is to ask an unsorted source for just its first 50 rows, sort those,
> and merge — bounded memory, no backend change. It does not work.
>
> An unsorted source's "first 50" is an arbitrary 50, not its smallest 50. Sorting them gives a
> neatly ordered page of the *wrong rows*; the record that should have come first globally may sit
> in that backend's page 7, and by then the merge has moved past it. Output looks right and
> quietly omits rows. Widening the window shrinks the error rate without ever reaching zero, which
> is worse — it passes casual testing and fails in production.
>
> For an unsorted source there are exactly two honest options: **read it whole**, or **make the
> backend sort**. There is no bounded middle.

### Mixing is fine

Sortedness is declared per source, so a route can stream its sorted backends while materialising
one laggard:

```yaml
sources:
  - service: orders-eu     # streamed
    presorted: true
  - service: legacy-orders # buffered whole, sorted here
    presorted: false
```

The heap only needs each input ordered — not how it got that way. Cost is set by the unsorted
source, so treat this as a migration step, not a destination.

### HTTP sources

A `REST_DOWNSTREAM` service can be a source too. Name it by the synthetic service the gateway
derives from its OpenAPI document (service `legacy` → `rest.legacy.LegacyService`, the
`operationId` as the rpc) and set `endpoint` to the service name:

```yaml
sources:
  - endpoint: orders-grpc
    service: sample.merge.OrderService
    rpc: listOrders
    presorted: true
  - endpoint: legacy                    # a REST_DOWNSTREAM service named `legacy`
    service: rest.legacy.LegacyService
    rpc: listOrders                     # its operationId
```

The operation's array response is split into rows (one per element of `items`), bound to the
synthetic message the document describes, and merged with the gRPC rows: the comparator resolves
the sort field per record, so the two sides only need to agree on field names, not descriptors.
The merge route itself must be a gRPC-backed route (a `REST_DOWNSTREAM` path is proxied before
the merge is consulted), and the service needs GraphQL enabled for it, since that is what builds
the descriptors.

An HTTP source is always **unsorted**: the gateway has no way to hand a REST operation
`sort_fields` or a page window in the merge's terms — its query parameters are whatever the
document declares — so it is read whole and ordered in the gateway on every page, and
`presorted: true` on it is ignored with a warning at load. The cost column below applies. A
non-2xx answer fails the source with the status the REST surface would have mapped it to, and
`failure-policy` applies as for any other source.

### Choosing

| | Pre-sorted | Unsorted |
| --- | --- | --- |
| Backend needs | honours `sort_fields` | nothing |
| Gateway memory | O(sources) | O(that source's whole result) |
| Rows fetched per page | a bounded window | all of them, every page |
| Safe for unbounded results | yes | no |
| Deep paging | resumes from the backend's own token | re-reads from the top each page |

`presorted` defaults to **false**. The gateway can send `sort_fields` but cannot verify a backend
honoured it, so it never assumes ordering it has not been promised. Declaring an unsorted source
`presorted: true` does not fail loudly — it returns misordered pages.

## Declaring a merge

Three ways, resolved first-match-wins: **SPI bean → YAML → proto annotation**. All three produce the
same `MergeSpec`, so none of them can drift in behaviour from the others.

### YAML

```yaml
shiguchi:
  merge:
    routes:
      - path: /v1/orders
        method: GET
        tie-break-field: id
        sources:
          - service: orders.OrderService
            rpc: listOrders
            presorted: true
          - service: legacy.OrderService
            rpc: list
            presorted: false
```

### Proto annotation

Lowest precedence, because it is the least dynamic — a merge baked into the `.proto` travels with
the API definition, which suits a fixed topology and not one that varies by deployment:

```protobuf
rpc listOrders(ListOrdersRequest) returns (stream Order) {
  option (google.api.http) = { get: "/v1/orders" };
  option (shiguchi.api.merge) = {
    tie_break_field: "id"
    sources: [
      { endpoint: "orders-eu", full_service_name: "orders.OrderService", method_name: "listOrders", presorted: true },
      { endpoint: "orders-us", full_service_name: "orders.OrderService", method_name: "listOrders" }
    ]
  };
}
```

Declared on one participant method listing the others: the route model is one method to one
binding, so a merge needs a primary to hang off.

### SPI

Highest precedence, for topologies computed at runtime (per tenant, per region). Same shape as
`GatewayAuthorizer`:

```java
@Bean
MergeSourceProvider regionalOrders() {
    return route -> "/v1/orders".equals(route.getHttpPath())
            ? Optional.of(new MergeSpec(sourcesForActiveRegions(), "id", UNKNOWN, FAIL_FAST, 0))
            : Optional.empty();
}
```

## Paging

No single backend token can express a merged position: position is how far into *every* source at
once, and no backend knows the others exist. The gateway mints its own cursor — opaque to callers,
returned in `X-Next-Page-Token` exactly like any other paged endpoint.

Inside, it holds the last emitted record's sort key plus, per source, that backend's own token and
how many rows it contributed *at that key*. That last count is what makes ties safe: when several
records share a sort value and the page boundary lands inside the group, the count says how far
into it each source had got, so the next page neither repeats nor skips rows.

Sources are addressed by **ordinal**, so reordering a route's source list invalidates outstanding
cursors — the gateway rejects them rather than silently resuming at the wrong place. Cursors are
gzipped and size-capped; a merge over many sources with large backend tokens will fail at mint
time rather than emit something a proxy would truncate.

### Ties need a tie-break field

If the sort key is not unique, "which of these did we already return?" is ambiguous at a page
boundary. Set `tie-break-field` to something stable and unique — usually the id:

```yaml
tie-break-field: id
```

It is appended as the final sort key. Without it, pages can repeat or drop tied rows.

### `total_count`

Defaults to `-1`, "not computed" — the honest answer. Summing per-source totals is only correct
when the sources hold **disjoint** rows; overlapping id spaces double-count. If you have verified
disjointness, opt in:

```yaml
total-count-strategy: SUM
```

It still falls back to `-1` at runtime if any source reports `-1`.

## When a source fails

With N backends, the odds one fails approach certainty.

- **`FAIL_FAST`** (default) — any source erroring fails the page. A merge that quietly drops a
  source returns a page that looks right and is missing rows, with nothing to tell the caller.
- **`BEST_EFFORT`** — serve the surviving sources and name the failed ones. Opt in for routes
  where a degraded page genuinely beats an error.

## Cost

Merging reads the sort field off every record, so merge routes always decode messages — they
cannot use the Tier 0 wire→JSON fast path. Same trade-off the gateway already makes when a
response carries `page_info`, just unconditional here.

Per page, gateway-side cost is O(page size) and does **not** grow with page depth: each source
resumes from its own token rather than re-walking. The exception is unsorted sources — every
HTTP source among them — which are re-read in full on every page; another reason to treat
materialisation as temporary. `MergeEndToEndBenchmark` (`mixedPage`) measures a merge with one
HTTP source next to the all-gRPC `mergePage`.

## Guardrails

| Setting | Default | Why |
| --- | --- | --- |
| `max-sources` | 8 | Blast radius. Validated at startup, not on a caller's page 2. |
| `window-size` | 3× page size | Lookahead for ties and exhaustion, bounded. |
| deadline | whole merge | The slowest source sets the pace; a merge page must complete. |

Cancellation propagates to every source, so a page that fills early closes the streams still open
rather than draining them.

There is no per-merge concurrency limiter: every source is called at once, so in-flight
sub-calls per page equal the source count, bounded only by `max-sources`.

## See also

- [`samples/sample-merge-gateway`](../samples/sample-merge-gateway) — runnable two-backend merge,
  one sorted and one not
- [projections-paging-sorting.md](projections-paging-sorting.md) — the `page_request` / `sort_fields`
  conventions a merge is built on, and how to implement them in a backend so its sources can stream
- [transcoding.md](transcoding.md) — the serde tiers a merge opts out of
- [configuration.md](configuration.md) — full property reference
