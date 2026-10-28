# shiguchi-ui-frontend

The React source for the gateway's embedded gRPC console, served at `/grpc-ui`.

The built assets are **committed** into `shiguchi-ui/src/main/resources` so consumers get a working
console without a Node toolchain. That means a change here is only live once the bundle is
rebuilt and both are committed together.

## Working on it

```bash
npm ci
npm run dev          # Vite dev server, proxying /grpc-ui/api to localhost:8080
```

Point it at a running gateway (any sample will do — `sample-grpc-ui-standalone` is the smallest).

| Command | What it does |
| --- | --- |
| `npm run dev` | dev server with HMR |
| `npm run build` | `tsc` then Vite — **writes into `shiguchi-ui`'s static resources** |
| `npm run lint` | oxlint |
| `npm test` | Vitest, once |
| `npm run test:watch` | Vitest, watching |
| `npm run test:coverage` | Vitest with a V8 coverage report |

CI runs lint, test and build, but only when a PR touches this directory.

## Why oxlint and not ESLint

This project is on **TypeScript 7**, and `typescript-eslint` supports `>=4.8.4 <6.1.0`. Without
it ESLint has no parser for these files at all — it fails on the first type annotation with
`Parsing error: Unexpected token {`, so it would lint nothing while appearing to pass.

Downgrading the compiler to satisfy a linter is the wrong way round. oxlint parses TypeScript
natively, has no `typescript` peer dependency, and carries the react-hooks rules, which are the
ones that catch real bugs rather than formatting opinions. `tsc` still runs as part of
`npm run build`, so type errors are caught either way.

### Findings are warnings, not errors

Turning the correctness category on surfaced **18 genuine pre-existing findings** — `Date.now()`
called during render (which makes a countdown update only when something else re-renders),
`setState` called synchronously inside effects, refs read during render. They are worth fixing,
but each is a behaviour change, and a linter arriving with a red build it did not cause gets
switched off rather than acted on.

So the categories are `warn`: the findings are visible on every run and in CI, and the job is
green because the lint step itself is not what is broken. Fixing them is follow-up work.

## Tests

Vitest with jsdom and Testing Library. The suite covers the two places the console keeps logic
rather than markup:

- **`src/store/useGrpcUiStore.test.ts`** — selecting a method clears the previous response,
  history caps at 20 and persists, restoring a history entry whose method no longer exists is a
  no-op rather than a cleared selection, the expanded-services set is replaced rather than mutated.
- **`src/api/grpcUiApi.test.ts`** — auth header selection (API-key provider vs bearer), blank
  metadata rows dropped, and the client-streaming compatibility contract: messages go out as
  `payloads` while `payload` stays set to the first of them, so an older gateway still sees a
  well-formed request.

That last one was checked by breaking it deliberately and confirming the test fails.

Components are not covered yet. The store and the API layer are where the behaviour lives; the
components are mostly markup over them.

## Layout

```
src/
  api/grpcUiApi.ts        fetch wrappers, auth header construction, SSE streaming
  store/useGrpcUiStore.ts all client state (zustand), history persistence
  components/             the panels: service tree, request editor, response viewer, auth
  types/schema.ts         the shapes the gateway's /grpc-ui/api/schema returns
```

`types/schema.ts` mirrors `GrpcUiSchemaDto` on the Java side. The two have to move together —
that class's Javadoc says so from the other direction.
