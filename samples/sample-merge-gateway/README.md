# sample-merge-gateway

One REST endpoint served by merging two gRPC backends and one plain HTTP backend that all
return the same order shape.

The three backends are deliberately unequal, because that is the interesting part:

| Backend | Port | Transport | Honours `sort_fields`? | What the gateway does |
| --- | --- | --- | --- | --- |
| `orders-eu` | 9101 | gRPC | yes | pushes the sort down, streams a bounded window |
| `orders-us` | 9102 | gRPC | no | reads it in full, sorts in memory |
| `legacy` | 9103 | HTTP + OpenAPI (`REST_DOWNSTREAM`) | cannot | reads it in full, sorts in memory |

All hold disjoint slices of the same logical order table (even ids in EU, odd in US, the next
even block in legacy), so a merged listing visibly interleaves them rather than showing one
region's block then the other's.

## Run it

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-merge-gateway:bootRun
```

All three backends start in-process; there is nothing else to run.

## Try it

```bash
# Merged, sorted by customer. Rows from both regions, interleaved.
curl 'localhost:8080/eu/v1/orders?sort=customer:asc&pageSize=5'

# Page forward with the cursor from the previous response.
curl -i 'localhost:8080/eu/v1/orders?sort=customer:asc&pageSize=5' | grep -i x-next-page-token
curl 'localhost:8080/eu/v1/orders?sort=customer:asc&pageSize=5&pageToken=<token>'

# Sort by a different key — the gateway re-pushes it to the sorted backend and re-sorts the other.
curl 'localhost:8080/eu/v1/orders?sort=total_cents:desc&pageSize=5'

# Each backend is still individually reachable, unmerged — the HTTP one is proxied as-is:
curl 'localhost:8080/us/v1/orders'
curl 'localhost:8080/legacy/orders'
```

Watch the logs: `MergeSpecResolver` reports at startup that two of the three sources are unsorted
and are therefore read in full on every page, and the legacy backend logs every `GET /orders` it
serves — one per merged page, with no sort or page parameters, because it has none.

## What to look at

- **`application.yml`** — the merge declaration. Note that the gRPC sources name an `endpoint`:
  they serve the *same* proto service, so without it they would be indistinguishable and the merge
  would read one backend twice. The HTTP source names the synthetic service the gateway derives
  from the OpenAPI document (`rest.legacy.LegacyService`, rpc = `operationId`).
- **`LegacyOrderHttpServer`** — the whole HTTP backend: an OpenAPI document and a JSON array.
  Nothing in it knows about the gateway; `docs/merging.md#http-sources` explains what the gateway
  does with the array.
- **`OrderServiceServer.listOrders`** — the `sorted` branch is the entire difference. Honouring
  `sort_fields` (one `result.sort(...)`) is what lets the gateway stream a backend instead of
  buffering it whole.
- **`tie-break-field: id`** — orders can share a customer or a timestamp. Without a stable last
  key, a page boundary landing inside a group of tied rows cannot tell which were already served,
  and pages can repeat or drop them.

## The trade-off this sample exists to show

Asking an unsorted backend for "the first 5 rows" returns an arbitrary 5, not the smallest 5.
Sorting those gives a neatly ordered page of the *wrong* rows — the record that should have come
first may sit further down that backend's result, and by then the merge has moved past it.

So an unsorted source has exactly two honest options: read it whole, or teach the backend to sort.
There is no bounded middle ground. `orders-us` is the "read it whole" case; making it behave like
`orders-eu` is a one-line change in `OrderServiceServer`, and worth trying to see the difference in
what the gateway requests.

Full reasoning: [docs/merging.md](../../docs/merging.md).
