# Proto options reference

Generated from the comments in `shiguchi/*.proto` by `ProtoOptionsReferenceTest`; edit the proto comments, then run
`./gradlew :shiguchi-proto:test -Dshiguchi.docs.update=true`. The build fails when this page is out of date.

Import `shiguchi/annotations.proto` for all of them, or one file on its own.

## `shiguchi/graphql.proto`

| Option | Applies to | Message |
| --- | --- | --- |
| `(shiguchi.graphql)` | `MethodOptions` | `GraphQlRule` |
| `(shiguchi.field)` | `FieldOptions` | `FieldRule` |
| `(shiguchi.type)` | `MessageOptions` | `TypeRule` |

### `GraphQlRule`

Method option: bind a gRPC method to a GraphQL field.
Usage: option (shiguchi.graphql) = { name: "listBooks", type: QUERY };

Settings that apply to every surface (scopes, caching, object-level authorization) live in
(shiguchi.method), see method.proto.

| Field | Type | Description |
| --- | --- | --- |
| `name` | `string` | GraphQL field name (e.g. "listBooks"). Required to enable GraphQL binding. |
| `type` | `Op` |  |
| `description` | `string` |  |
| `deprecation_reason` | `string` |  |
| `parent_type` | `string` | For RESOLVER type: the GraphQL type that this field is added to. |
| `arg_fields` | `repeated string` | Expose only these proto field names (snake_case) as individual top-level GraphQL args. |
| `pluck` | `string` | Extract this proto field name (snake_case) as the GraphQL return value. |
| `loader_for` | `string` | Declares this method is a loader for the named GraphQL type. The method must accept a repeated ID field (identified by loader_ids_field) and return a repeated result field (identified by loader_result_field). When set, the gateway registers a DGS DataLoader for automatic N+1 resolution. Works for both single-ID deduplication and true batch calls. Example: loader_for: "Author" |
| `loader_ids_field` | `string` | Proto field name (snake_case) in the request message that carries the list of IDs. Required when loader_for is set. Example: loader_ids_field: "ids" |
| `loader_result_field` | `string` | Proto field name (snake_case) in the response message that carries the result objects. Defaults to the first repeated message field in the response if not specified. Example: loader_result_field: "types" |
| `cost` | `uint32` | Cost of this field for shiguchi.graphql.limits.max-complexity and the per-caller budget: the points charged when a query selects it (the fields it selects underneath add their own). A list field is multiplied by its `first` argument, or by shiguchi.graphql.limits.default-list-size when the query sends none. 0 = default weight of 1. Example: cost: 5 |
| `directives` | `repeated string` | Raw SDL directives appended verbatim to the GraphQL field, e.g. '@tag(name: "internal")'. The gateway does not validate directive names or arguments; the composition layer does. |
| `live` | `bool` | Live queries (`query ... @live`): set to false to keep this method out of them. A query that selects such a field runs once, like an ordinary query. Unset: the method may be live. |
| `invalidated_by` | `repeated string` | Cursus topic patterns (same syntax as the push topics) whose events change this method's result. An event on one of them re-runs the live queries that select the field. |

#### `Op` (enum)

GraphQL operation type for a gRPC method binding.

| Value | Description |
| --- | --- |
| `QUERY` |  |
| `MUTATION` |  |
| `SUBSCRIPTION` |  |
| `RESOLVER` |  |

### `FieldRule`

Field option: customize how a proto field is exposed in GraphQL.
Named `field` rather than `graphql_field` because most of it (required, name, omit,
description, deprecation_reason, default) is not GraphQL-specific; other surfaces may
honor these settings later.
Usage: string author_id = 3 [(shiguchi.field) = { resolves_type: "Author" }];

| Field | Type | Description |
| --- | --- | --- |
| `required` | `bool` | Emit field as non-nullable (Type!) in GraphQL schema. |
| `id` | `bool` | Use GraphQL ID scalar instead of the inferred type. |
| `omit` | `bool` | Exclude this field from the GraphQL schema entirely. |
| `name` | `string` | Override the GraphQL field name (aliasing without changing the proto). |
| `default` | `string` | Inject a default value in input types (e.g. "50" for Int fields). |
| `description` | `string` | Doc-string emitted as a GraphQL description. |
| `deprecation_reason` | `string` | Emits @deprecated(reason: "...") when non-empty. |
| `resolves_type` | `repeated string` | Declares this field is a reference to one or more GraphQL types by ID. Each value is a GraphQL type name the ID resolves to. The gateway registers a DGS DataLoader-backed resolver per type. For a single type the resolver GraphQL field name is derived from the proto field name (strip trailing _id, camelCase). For multiple types the name is additionally derived from the type name (e.g. Author→author, AuthorRef→authorRef). Example (single): string author_id = 3 [(shiguchi.field) = { resolves_type: "Author" }]; Example (multi): string author_id = 2 [(shiguchi.field) = { resolves_type: "Author", resolves_type: "AuthorRef" }]; |
| `batch_key` | `bool` | Marks this field as the primary identifier used for DataLoader batch keying. Distinct from `id` (which controls GraphQL scalar type): batch_key controls which field the gateway uses to index results when resolving batch gRPC calls. Example: int64 id = 1 [(shiguchi.field) = { id: true, batch_key: true }]; |
| `cost` | `uint32` | Cost of selecting this field, for shiguchi.graphql.limits.max-complexity and the per-caller budget. Same rules as GraphQlRule.cost: a repeated field is multiplied by the list size. 0 = default weight of 1. Example: bytes payload = 4 [(shiguchi.field) = { cost: 20 }]; |
| `shareable` | `bool` | Federation: emit @shareable (the field may be resolved by more than one subgraph). |
| `external` | `bool` | Federation: emit @external (the field is owned by another subgraph). |
| `requires` | `string` | Federation: emit @requires(fields: "..."). A selection set, validated when the schema is built. |
| `provides` | `string` | Federation: emit @provides(fields: "..."). A selection set, validated when the schema is built. |
| `directives` | `repeated string` | Raw SDL directives appended verbatim to the field, e.g. '@tag(name: "internal")'. Not validated by the gateway. |

### `TypeRule`

Message option: how a proto message is exposed as a GraphQL object type.
Usage: option (shiguchi.type) = { keys: "id" };

| Field | Type | Description |
| --- | --- | --- |
| `keys` | `repeated string` | Federation @key field sets, one @key directive each ("id", "orgId id"). When empty, the field marked batch_key is the key. Entity resolution (_entities) uses the first single-field key; composite keys are published in the SDL only. |
| `unresolvable` | `bool` | Emit @key(resolvable: false): other subgraphs may reference the type, this one cannot resolve it. |
| `directives` | `repeated string` | Raw SDL directives appended verbatim to the type, e.g. '@tag(name: "internal")'. |
| `description` | `string` | Doc-string emitted as the GraphQL type description. |

## `shiguchi/method.proto`

| Option | Applies to | Message |
| --- | --- | --- |
| `(shiguchi.method)` | `MethodOptions` | `MethodRule` |

### `MethodRule`

Method option: settings that apply to a gRPC method on every surface it is exposed on
(REST, GraphQL, gRPC-Web, ...).
Usage:
  option (shiguchi.method) = {
    auth_scopes: "orders.read"
    cache_ttl_seconds: 30
    authz: [{ object_type: "order", relation: "viewer", id_field: "order_id" }]
  };

| Field | Type | Description |
| --- | --- | --- |
| `auth_scopes` | `repeated string` | OAuth2/JWT scope names enforced before dispatch (empty = no check). |
| `cache_ttl_seconds` | `int32` | Adds Cache-Control: max-age=N to REST responses, and is the TTL of the GraphQL response cache (shiguchi.graphql.response-cache) for queries whose root fields all resolve to a method with one. |
| `authz` | `repeated AuthzRule` | Object-level authorization rules enforced before dispatch (empty = no object check). Unlike auth_scopes -- which asks "may this caller call this method?" -- these ask "may this caller act on THIS object?". The gateway itself does not evaluate them: they are carried on the route for a GatewayAuthorizer implementation (OpenFGA, OPA, Cedar, ...) to consume. When no authorizer consumes them they are inert, so the same .proto works unchanged behind a gateway with no policy engine wired. All rules must pass for the call to proceed. |

### `AuthzRule`

One object-level authorization requirement: the caller must hold `relation` on the object
`object_type:<value of id_field>`.

Example -- caller must be a viewer of the requested order:
  option (shiguchi.method) = {
    authz: [{ object_type: "order", relation: "viewer", id_field: "order_id" }]
  };

| Field | Type | Description |
| --- | --- | --- |
| `object_type` | `string` | Object type in the policy engine's model, e.g. "order". Required. |
| `relation` | `string` | Relation/permission the caller must hold on that object, e.g. "viewer". Required. |
| `id_field` | `string` | Proto field name (snake_case) in the REQUEST message whose value is the object id. Resolved against the request the caller actually sent: a REST path variable or query parameter, or a GraphQL field argument. Required. Nested fields are addressed with dots, e.g. "order.id". |

## `shiguchi/merge.proto`

| Option | Applies to | Message |
| --- | --- | --- |
| `(shiguchi.merge)` | `MethodOptions` | `MergeRule` |

### `MergeSource`

One backend participating in a merged endpoint.

| Field | Type | Description |
| --- | --- | --- |
| `endpoint` | `string` | The gateway service registration this source lives in (the `name` under shiguchi.services). Optional, but required when several registered backends expose the SAME proto service -- the sharded case, where every shard serves an identical .proto and only the address differs. Without it those sources are indistinguishable and the merge would read one backend repeatedly instead of all of them. |
| `full_service_name` | `string` | Proto service FQN, e.g. "orders.OrderService". Required. |
| `method_name` | `string` | RPC name on that service. Required. |
| `presorted` | `bool` | Whether this backend returns its rows ALREADY ORDERED by the merge key. The single most consequential field here. A k-way merge is a merge of sorted sequences: it compares only the record currently at the front of each source, so a record that sorts earlier but sits further down an unsorted stream is never reconsidered. Declaring an unsorted backend pre-sorted does not fail loudly -- it returns pages that look ordered and quietly omit rows. The gateway cannot check the claim: it can send sort_fields, but not verify the backend honoured it. Defaults to false, and a false source is read in full and sorted gateway-side. |

### `MergeRule`

Method-level annotation: serve this route by merging several backends that return the same
message type, ordered by the caller's sort.

Declared on one participant method, listing the others -- the route model is one method to one
binding, so a merge needs a primary to hang off. A MergeSourceProvider bean or YAML config both
override this; see docs/guides/merging.md.

Example:
  option (shiguchi.merge) = {
    tie_break_field: "id"
    sources: [
      { endpoint: "orders-eu", full_service_name: "orders.OrderService", method_name: "list", presorted: true },
      { endpoint: "orders-us", full_service_name: "orders.OrderService", method_name: "list" }
    ]
  };

| Field | Type | Description |
| --- | --- | --- |
| `sources` | `repeated MergeSource` | Participants. Order is significant: a page cursor addresses sources by their index here, so reordering this list invalidates every cursor already handed out. At least two required. |
| `tie_break_field` | `string` | Stable, unique field appended as the final sort key. Without one, records sharing a sort value have no defined relative order, and a page boundary landing inside such a group cannot say which rows it already served -- so pages can repeat or drop them. Usually the id. |
| `total_count_strategy` | `string` | "UNKNOWN" (default, reports -1) or "SUM". Summing is only correct when the sources hold disjoint rows; overlapping id spaces double-count. |
| `failure_policy` | `string` | "FAIL_FAST" (default) or "BEST_EFFORT". Failing fast is the default because a merge that quietly drops a failed source returns a page that looks right and is missing rows. |
| `window_size` | `int32` | Records fetched per source per page; 0 uses the configured window multiplier. |

## `shiguchi/topic.proto`

| Option | Applies to | Message |
| --- | --- | --- |
| `(shiguchi.topic)` | `MethodOptions` | `UpstreamTopic` |

### `UpstreamTopic`

| Field | Type | Description |
| --- | --- | --- |
| `pattern` | `string` | `namespace:template`, the template naming request fields in braces: "orders:{order_id}", "tenant:{tenant_id}/{stream}". Required. |
| `mode` | `Mode` | How the upstream stream is shared. Only SHARED_UPSTREAM exists today. |
| `event_id_field` | `string` | Optional: a field of the response message (dots reach into nested messages: "meta.sequence") holding the backend's own increasing sequence number. It becomes the push event id where the push store keeps ids per topic (MEMORY, REDIS), so the ids clients resume from survive gateway restarts and owner handoffs; everywhere it is also recorded as the `upstream.id` attribute. An integer field, or a string holding a decimal integer; a value of zero or less counts as missing and the event gets the store's own id. Plain server-streaming SSE routes of the method emit it as the `id:` line. |

#### `Mode` (enum)

| Value | Description |
| --- | --- |
| `SHARED_UPSTREAM` | One upstream stream per topic, owned by one gateway instance, fanned out to every subscriber of the topic through the push engine. |

## `shiguchi/pagination.proto`

### `PageRequest`

How much of a collection to return, and where to start.

Put it on a list request under the field name `page_request`:

    message ListBooksRequest {
      PageRequest page_request = 1;
    }

The gateway fills it from `?pageSize=` / `?pageToken=` on REST and from Relay's `first:` /
`after:` on GraphQL. Your backend decides what the token means -- it is opaque to the gateway.

| Field | Type | Description |
| --- | --- | --- |
| `page_size` | `int32` | Maximum items to return. Treat 0 as "your default" and clamp large values: a client should not be able to ask for the whole table. |
| `page_token` | `string` | Opaque cursor identifying where this page starts, echoed back from a previous response's `PageInfo.next_page_token`. Blank means the first page. Any encoding works as long as your service both produces and consumes it. |

### `PageInfo`

Where the caller is in a collection, returned alongside the items.

Put it on a list response under the field name `page_info`, next to a repeated items field:

    message ListBooksResponse {
      repeated Book books   = 1;
      PageInfo    page_info = 2;
    }

The repeated field is required for detection -- it is what makes the GraphQL Relay Connection
transform well-defined. On REST this becomes the `X-Next-Page-Token` and `X-Total-Count`
headers; on GraphQL it becomes `pageInfo`.

| Field | Type | Description |
| --- | --- | --- |
| `next_page_token` | `string` | Cursor for the next page. Leave it blank on the last page: the gateway omits the `X-Next-Page-Token` header entirely when it is blank, and that absence is how REST clients detect the end of a collection. |
| `total_count` | `int32` | Total items across all pages. Optional, and it usually costs a COUNT query on every request -- leave it unset unless a caller genuinely needs the total. Unset is reported as absent rather than as zero. |
| `prev_page_token` | `string` | Cursor back to the start of the current page. Optional. Set it when your backend can produce one cheaply and GraphQL clients can then page backwards: it answers Relay's `startCursor` and `hasPreviousPage`. Without it the gateway falls back to the `after` cursor the caller paged in on, which is still honest but only proves a previous page exists once they have moved off the first one. |

### `SortField`

One sort key. Put a repeated one on a list request under the field name `sort_fields`:

    message ListBooksRequest {
      repeated SortField sort_fields = 2;
    }

The gateway expands `?sort=author:asc,year:desc` into this list, in order, and GraphQL emits a
matching `SortFieldInput`. Order is significant: the first entry is the primary key.

| Field | Type | Description |
| --- | --- | --- |
| `field` | `string` | A field name on the resource being listed. The gateway forwards this string without checking it against your message -- resolve it against an allow-list in your backend rather than interpolating it into a query. |
| `sort_order` | `SortOrder` | Direction for this key. |

### `SortOrder` (enum)

Sort direction. `SORT_ORDER_UNSPECIFIED` is treated as ascending, matching what the gateway
does with a bare `?sort=author` that carries no `:direction` suffix.

| Value | Description |
| --- | --- |
| `SORT_ORDER_UNSPECIFIED` |  |
| `SORT_ORDER_ASC` |  |
| `SORT_ORDER_DESC` |  |
