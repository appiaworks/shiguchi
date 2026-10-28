# observability

Prometheus and Grafana for any of the samples, with the gateway's dashboard already on the
board when Grafana opens. Not a sample of its own — the gateway is whichever sample you start
with `bootRun` on the host; the two containers here only scrape it and draw it.

```
sample (bootRun, :8080) ──/actuator/prometheus──▶ prometheus (:9091) ──▶ grafana (:3000)
```

## Run it

Start a sample that exposes `/actuator/prometheus`. `sample-discovery-gateway` is the best
first pick — it runs its own three-instance backend, so every panel has something to show:

```bash
./gradlew publishToMavenLocal          # from the repo root, once
cd samples && ../gradlew :sample-discovery-gateway:bootRun
```

Then, from this directory:

```bash
docker compose up
```

Open <http://localhost:3000>. No login; the **Shiguchi** dashboard is provisioned
from [`docs/grafana/shiguchi.json`](../../docs/grafana/shiguchi.json) and
Prometheus is already its data source. Prometheus itself is on <http://localhost:9091> (9090 on
the host is where the samples expect their gRPC backend, so it moved).

The panels stay empty until something calls the gateway. Keep it busy:

```bash
./load.sh                                   # 10 req/s, one 404 in ten, until Ctrl-C
RPS=50 ./load.sh
./load.sh http://localhost:8080 /v1/products/A100 /v1/products   # other routes
```

Within a refresh the traffic, error and latency panels fill in. Now do something to the
gateway and watch the state panels react:

```bash
curl -s -X DELETE localhost:8080/fleet/one     # Discovery instances: 3 → 2
```

## Which samples expose metrics

Any sample with `micrometer-registry-prometheus` on the classpath and `prometheus` in
`management.endpoints.web.exposure.include`. Three do, and each lights up a different corner
of the dashboard:

| Sample | What it adds |
| --- | --- |
| `sample-discovery-gateway` | `gateway_discovery_instances`; kill an instance and the gauge drops |
| `sample-rate-limited-gateway` | `gateway_ratelimit_requests_total{outcome}`; a shell loop shows `rejected` climbing and 429s on the HTTP panel |
| `sample-full` | every surface at once, so REST, GraphQL and console traffic all land in the same backend-call panels |

Point `prometheus.yml` at the sample's port if it is not 8080, and at more than one target to
see the dashboard's `instance` variable do something.

All three also set `management.metrics.distribution.percentiles-histogram` for the gateway's
call timer and Spring's request timer. Without that a Micrometer timer exports only `_count`,
`_sum` and `_max`, and the latency panels show *no histogram buckets* rather than a percentile.
The other samples work with this stack too once they get the dependency and those two
properties — copy them from one of the three.

## Files

| File | Purpose |
| --- | --- |
| `docker-compose.yml` | Prometheus + Grafana; Grafana mounts the dashboard from `docs/grafana` so the repository has one copy of it |
| `prometheus.yml` | One scrape job, `shiguchi`, against `host.docker.internal:8080` every 5 s |
| `grafana/provisioning/` | Data source and dashboard provider; nothing to click through |
| `load.sh` | Traffic generator |

Edits made in the Grafana UI are not written back to the file (the provider is read-only on
purpose). To change the dashboard, export it from Grafana's share menu as JSON and replace
`docs/grafana/shiguchi.json`.

→ [docs/observability.md](../../docs/observability.md) for what each metric means and the
PromQL behind the panels
