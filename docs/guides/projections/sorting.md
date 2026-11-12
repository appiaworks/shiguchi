# Sorting

Part of the [projections guide](index.md).

## Sorting

A request with a `sort_fields` field accepts a `sort` argument on both surfaces.

**REST** takes a single comma-separated string:

```bash
curl 'http://localhost:8080/v1/books?sort=author:asc,year:desc'
```

Syntax: comma-separated `field[:asc|desc]`. Direction is case-insensitive and **defaults to
ascending** when the `:direction` suffix is absent (`?sort=author` ≡ `?sort=author:asc`).
Any value other than `desc` is treated as ascending.

**GraphQL** takes a `[SortFieldInput]` list — the schema builder emits the `SortFieldInput`
input type and `SortOrder` enum automatically for any field whose request has `sort_fields`:

```graphql
query {
  listBooks(sort: [
    { field: "author", sortOrder: SORT_ORDER_ASC },
    { field: "year",   sortOrder: SORT_ORDER_DESC }
  ]) { edges { node { id title } } }
}
```

Both spellings produce the same request field:

```json
{ "sortFields": [
    { "field": "author", "sortOrder": "SORT_ORDER_ASC" },
    { "field": "year",   "sortOrder": "SORT_ORDER_DESC" }
] }
```

Entries with a missing or blank `field` are skipped. A bare `"asc"` / `"desc"` is accepted
where the enum is expected and normalised to the `SORT_ORDER_*` form.

The gateway does not validate that `field` names a real field on your resource — that
check belongs to the backend, which should reject unknown sort keys rather than
interpolating them into a query. [`SortSpec`](../../reference/backend-support.md#sortspec) is that allow-list, and the
[JPA example](jpa-example.md) shows it in use.

> There is no `order_by` convention. `sort_fields` / `?sort=` is the only spelling the
> gateway recognises.

---
