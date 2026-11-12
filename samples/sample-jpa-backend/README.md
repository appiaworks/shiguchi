# sample-jpa-backend

The **backend** behind a list method, not the gateway: a Spring Data JPA service that honours the
three fields the gateway fills in for a list request, with
[`shiguchi-backend-support-jpa`](../../docs/guides/projections/jpa-example.md).

| The gateway sends | From | The backend does |
| --- | --- | --- |
| `page_request` | `?pageSize=` `?pageToken=` | clamps the size, decodes the token, fetches one row more than the page, issues the next token |
| `sort_fields` | `?sort=publishedYear:desc` | rejects keys outside an allow-list, maps `published_year` to the `year` property, appends `id` as a tie-breaker |
| `read_mask` | `?fields=title` | reads only those columns (and `id`, which is always fetched) |

The whole backend is `BookBackend.listBooks`: about twenty lines of logic. The allow-listing,
column selection, paging, cursor and message mapping are in the library. The gateway is in the
same process so there is nothing else to start; the schema comes from the generated stubs
(`mode: STATIC`), so the backend needs no reflection service.

## Run it

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-jpa-backend:bootRun
```

```bash
curl -i 'localhost:8080/v1/books?pageSize=3&sort=publishedYear:desc'
curl -i 'localhost:8080/v1/books?pageSize=3&fields=title'
curl -i 'localhost:8080/v1/books?sort=password:asc'      # 400: not in the allow-list
curl -i 'localhost:8080/v1/books?pageToken=forged'       # 400: token fails its signature
```

Follow `X-Next-Page-Token` to walk all ten books. `X-Total-Count` comes from a `COUNT` query;
drop `.toPageInfo(query.count())` to `.toPageInfo()` if you do not need it.

## Offset or keyset

`sample.paging` in `application.yml` picks the token's contents. The gateway contract is
identical, and the tests run the same assertions against both.

```bash
../gradlew :sample-jpa-backend:bootRun --args='--sample.paging=keyset'
```

Offset tokens drift if rows change while someone is paging; keyset tokens carry the last row's
sort key and do not. Keyset needs non-null sort columns and one sort for the whole listing.

## Things worth seeing

- **`published_year` vs `year`.** The proto field and the JPA property differ; `SortSpec` and
  `MaskSpec` carry the mapping, and `ProjectionQuery` fills the proto field from the right
  column. `?sort=publishedYear:desc` and `?sort=published_year:desc` both work.
- **Fields not selected come back as proto defaults** (`""`, `0`): they were never read from the
  database. Declare a field `optional` to have it omitted from the JSON instead.
- **`UnaryCalls.respond`.** Without it grpc-java reports a thrown `INVALID_ARGUMENT` as
  `UNKNOWN`, and the gateway answers a bad sort key with a 500 instead of a 400.
- **The cursor secret** (`sample.cursor-secret`) is a placeholder. Use a real one, shared by every
  instance, in a real deployment; changing it invalidates tokens already issued.
