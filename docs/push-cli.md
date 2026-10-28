# cursus: the Cursus command line

Experimental. `cursus` subscribes, publishes, lists and searches a gateway's
[push surface](push.md) from a terminal. Output is NDJSON on stdout (one JSON document per line,
flushed as it is produced) and diagnostics go to stderr, so it composes with `jq`, `grep` and
shell loops. It is a plain Java program (picocli, the JDK's HTTP client and WebSocket, gRPC stubs,
Jackson), no Spring, started with Java 21 or newer.

Verbs: `sub`, `pub`, `ls`, `find`, and for operators `repub`, `rm`, `dlq`, `seek`, `send` and `bench`.

## Install

```bash
# jbang (fetches the jar from the Maven repository the catalog names)
jbang cursus@appiaworks/shiguchi sub 'orders:*'      # catalog: jbang-catalog.json at the repo root
jbang alias add --name cursus io.github.appiaworks:shiguchi-cursus-cli:LATEST

# the fat jar (also attached to each GitHub Release as cursus-<version>-all.jar)
./gradlew :shiguchi-cursus-cli:shadowJar                  # build/libs/cursus-<version>-all.jar
java -jar cursus-0.14.0-all.jar sub 'orders:*'

# the wrappers in bin/ (sh and cmd) run the jar next to them, in the build output, or $CURSUS_JAR
bin/cursus sub 'orders:*'
```

GraalVM native is not built (native builds are heavy on the CI runners); revisit if start-up time matters.

## Connecting

| Option | Environment | Meaning |
| --- | --- | --- |
| `--url` | `CURSUS_URL` | the gateway, default `http://localhost:8080` |
| `--actuator-url` | `CURSUS_ACTUATOR_URL` | where `gateway-push` lives, default `<url>/actuator` |
| `--push-path` | | the push path, default `/push` |
| `--grpc-target` | | `host:port` of the gRPC port for `--grpc`, default the URL's host on 9090 |
| `--token` | `CURSUS_TOKEN` | bearer token |
| `--token-cmd` | `CURSUS_TOKEN_CMD` | a command whose stdout is the token; run again after a 401 and the request retried once |
| `--basic user:pass` | | for the actuator endpoint, instead of the token |
| `--insecure` | | do not verify TLS certificates; warns once on stderr; never the default |
| `--profile` | `CURSUS_PROFILE` | a profile of the config file |
| `--pretty` | | tables and one line per event instead of NDJSON |
| `-q`, `--quiet` | | no diagnostics on stderr |

Options are inherited by every verb and can go before or after it. Flags win over the
environment, which wins over the profile.

### Profiles

`~/.cursus/config.yml` (or the file named by `CURSUS_CONFIG`):

```yaml
profiles:
  default:                       # used when no profile is named
    url: http://localhost:8080
  staging:
    url: https://gw.staging.example.com
    actuator-url: https://gw.staging.example.com/actuator
    token-cmd: "oidc-token --audience gw"
    basic: ops:secret            # actuator only
    insecure: false
    push-path: /push
    grpc-target: gw.staging.example.com:443
```

## sub

`cursus sub TOPIC... ` (aliases `subscribe`, `tail`) prints one line per event:

```bash
cursus sub orders:123 ticker:btc
# {"topic":"orders:123","id":"1759953600000000042","data":{"status":"SHIPPED"}}
cursus sub orders:123 --after 1759953600000000042 -n 10 --ws
cursus sub jobs:q --group workers --member w1 --ack          # gRPC, acknowledges each event
```

A line is `{topic, id, data}` plus `attributes`, `key` and `time` where the transport carries them
(WebSocket and gRPC do; the SSE frame carries the payload only). A typed gRPC payload appears as
`message` with its `@type`.

| Option | Meaning |
| --- | --- |
| `--sse` (default), `--ws`, `--grpc` | the transport; gRPC is the default with `--group` or `--ack`, since SSE cannot ack inline (it falls back to `POST <path>/ack`) |
| `--after ID` | resume after this event id |
| `--group G --member M` | join a consumer group (`member` defaults to host-pid) |
| `--session S` | a client session id |
| `--origin ID` | this subscriber's own origin id: events published with the same origin are not delivered to it (echo suppression, see [push.md](push.md#echo-suppression)); ignored with `--group` |
| `--qos` | `AT_MOST_ONCE` or `AT_LEAST_ONCE` |
| `--ack` | acknowledge each event after printing it |
| `-n`, `--max-events N` | exit 0 after N events |
| `--timeout SECONDS` | exit 0 after that long |
| `--retries N` | give up with exit 1 after N failed reconnects (default: never) |

**Resume.** `sub` keeps running across the stream ending: it remembers the last id and
reconnects with `Last-Event-ID` (SSE), `after` (WebSocket, gRPC), with exponential backoff
(500 ms doubling to 30 s) and jitter. `event: close` and gRPC `UNAVAILABLE` with
`push-close-reason` reconnect; `overflow` prints `{"overflow":{...}}` and resumes from the last
id; `reset` (the resume point is older than the history the gateway keeps, or the namespace keeps
none) prints `{"reset":{...}}` and keeps reading the same stream: the retained events follow, and
a script that wants a fresh start should refetch its state when it sees the line. A 401 with `--token-cmd` runs the command and retries once before counting as a failure.

## pub

`cursus pub TOPIC... [-d DATA]` (alias `publish`) posts to `<path>/publish` and prints the
gateway's answer, one line per event:

```bash
cursus pub orders:123 -d '{"status":"SHIPPED"}' --attr tenant=acme --key order-123
cursus pub orders:123 -d @event.json
cat events.ndjson | cursus pub orders:123 --batch 20       # one event per line
cursus pub orders:123 --type acme.OrderEvent -d @order.json  # typed, see below
```

`--origin ID` stamps the event's origin (`push.origin`), so a `cursus sub --origin ID` does not
print it. `--batch N` keeps N publishes in flight; the answers are printed in input order.
`--fire-and-forget` prints nothing and only reports failures on stderr. `--type` posts an
`Any` (`{"@type":"type.googleapis.com/acme.OrderEvent", ...}`) to `<path>/v1/publish`, the
transcoded `PushService.Publish`; the type must be one the gateway knows.

## ls

`cursus ls [topics|ns|conns|sessions|groups|info]`, default `topics`; the words also work as
commands (`cursus conns`). Reads the `gateway-push` actuator endpoint.

```bash
cursus ls topics --filter 'orders:'
cursus ls conns --principal alice --transport ws --pretty
cursus ls groups --limit 20
```

`--filter` is the endpoint's `q` (topic text, or a name prefix for sessions and groups).
`--principal`, `--topic` and `--transport` filter connections.

## find

`cursus find` (alias `search`) searches the archive, newest first; the gateway needs
`archive.retention` (without it the endpoint answers 404 and the exit code is 3).

```bash
cursus find --topic 'orders:*' --from 1h --attr tenant=acme
cursus find --topic orders:123 --text shipped --limit 20 --pretty
cursus find --topic 'orders:*' --from 2d --export > orders.ndjson
```

Options: `--topic`, `--from`, `--to` (an ISO instant or `30m`, `1h`, `2d` ago), `--after`,
`--before`, `--attr k=v` (repeatable), `--type`, `--text`, `--limit`. `--export` streams the
same query from `<path>/export` instead (NDJSON, up to 100 000 events). What a hit shows follows
the gateway's `admin.payload-visibility`.

## Admin verbs

These write through the `gateway-push` actuator endpoint, so they need the gateway's
`cursus.admin.role` (a token holding it, or `--basic`). The gateway audits each one.

### repub

`cursus repub` (alias `republish`) publishes archived events again, with `origin_id` linking
each copy to the original. One event by id, or everything a query matches (the same filters as
`find`):

```bash
cursus repub --topic orders:123 --id 0000018f --dry-run
cursus repub --topic 'orders:*' --from 1h --attr tenant=acme --target orders-replay --reason backfill
cursus repub --topic 'orders:*' --from 1d --no-wait
```

`--dry-run` says what would be sent and sends nothing. `--target` publishes to another topic,
`--reason` goes into the audit log. A query republish starts a job: `repub` prints it, then a
line each time the counters move, until it is `DONE` (exit 0); `FAILED` or `CANCELLED` exit 1.
`--no-wait` returns after the first line.

### rm

`cursus rm ID --topic TOPIC` (alias `tombstone`) redacts an archived event: the payload goes,
the id stays, resumes skip it. Prints `{"tombstoned":true,"topic":...,"id":...}`.

### dlq

`cursus dlq ls [--source S] [--limit N]`, `cursus dlq retry ID [--topic T]` (publish the dead
letter again, to its intended topic or `T`) and `cursus dlq rm ID`.

### seek

`cursus seek --group G | --session K --topic T --to ID|first|head` moves a consumer group's or
a persistent session's cursor on one topic: `first` replays everything the history still has,
`head` skips to now. Exit 1 when the gateway reports it could not move the cursor.

### send

`cursus send --conn ID -d DATA` delivers an event to one connection, wherever in the cluster it
lives; `--user NAME` does so for every connection whose principal is `NAME` (found through the
connection list). `-d` takes JSON text or `@file`. One line per connection with its delivery
outcome (`DELIVERED`, `NOT_CONNECTED`, `TIMEOUT`, `REJECTED`); `--require-ack` waits for the
client to acknowledge (`--timeout-ms`, capped by the gateway). Exit 0 when something was delivered, 1
otherwise.

```bash
cursus send --user alice -d '{"notice":"maintenance at 22:00"}'
cursus send --conn 3f9c -d @event.json --require-ack --timeout-ms 5000
```

### bench

`cursus bench` is a quick probe of a deployment: publishers post timestamped events at a fixed
rate, subscribers read them over SSE, and one JSON object comes out with what was sent and
received and the publish-to-receive latency percentiles.

```bash
cursus bench --topic bench:1 --publishers 2 --subscribers 10 --rate 200 --duration 10
```

`--rate` is events per second in total; each subscriber sees each event, so `received` is about
`rate x duration x subscribers`. The timestamp is the client's own clock, so latency needs no
clock sync between publisher and subscriber, but it includes this machine's load and network. It
is a smoke test for a deployment, not a replacement for the repository's `PushBenchmark`.

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | ok (also `-n` or `--timeout` reached) |
| 1 | transport or HTTP error, reconnects exhausted |
| 2 | usage error |
| 3 | not supported by this gateway: 404 or 501 (push off, no archive, unknown namespace) |
| 4 | authentication: 401 (after a failed refresh) or 403 |
| 130 | interrupted (Ctrl-C), after flushing what was read |
