#!/usr/bin/env bash
# GraphQL side by side: Protocol-Lattice (Rust) and this gateway in front of the same
# greeter.Greeter/SayHello backend, same query, same load generator, all as podman containers.
# Rows (README of shiguchi-benchmarks): 1 lattice cache off, 2 lattice cache on, 3 this gateway
# GraphQL, 4 this gateway GraphQL with the document cache, 5 this gateway REST on bench.Orders, 6 this gateway GraphQL
# with its response cache (shiguchi.graphql.response-cache).
#
#   ./gradlew :shiguchi-benchmarks:jmhJar
#   (WSL) shiguchi-benchmarks/comparison/lattice/build.sh <lattice checkout>
#   shiguchi-benchmarks/comparison/lattice/run.sh
#
# DURATION (30s), CONCURRENCY (32), REPEATS (3): each repeat follows a 10 s warm-up at the same
# concurrency; the median req/s is reported. Results land in build/comparison/lattice-*.json.
set -euo pipefail
cd "$(dirname "$0")/../.."
JAR=$(ls -t build/libs/shiguchi-benchmarks-*-jmh.jar | head -1)
LATTICE=build/comparison/lattice-gateway
[ -f "$LATTICE" ] || { echo "run comparison/lattice/build.sh first"; exit 1; }
DURATION=${DURATION:-30s}; CONCURRENCY=${CONCURRENCY:-32}; REPEATS=${REPEATS:-3}
NET=gwlat; OUT=build/comparison
TEMURIN=docker.io/eclipse-temurin:25-jre
UBUNTU=docker.io/library/ubuntu:24.04
OHA=ghcr.io/hatoo/oha:latest
CURL=docker.io/curlimages/curl:latest
export MSYS_NO_PATHCONV=1
GQL='{"query":"{ hello(name:\"world\") { message } }"}'
REST='{"id":"order-1"}'

cleanup() { podman rm -f lat-backend lat-gateway >/dev/null 2>&1 || true; podman network rm -f $NET >/dev/null 2>&1 || true; }
trap cleanup EXIT; cleanup
mkdir -p "$OUT"; podman network create $NET >/dev/null

podman create --name lat-backend --network $NET -e PORT=9090 $TEMURIN \
  java -Xmx256m -cp /jmh.jar dev.appiaworks.shiguchi.bench.ComparisonBackend >/dev/null
podman cp "$JAR" lat-backend:/jmh.jar; podman start lat-backend >/dev/null
for i in $(seq 1 60); do podman logs lat-backend 2>&1 | grep -q "serving on" && break; sleep 1; done

up() { # url body
  for i in $(seq 1 90); do
    podman run --rm --network $NET $CURL -s -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' -d "$2" "$1" 2>/dev/null | grep -q 200 && return 0
    sleep 1
  done
  echo "did not come up: $1"; podman logs lat-gateway 2>&1 | tail -20; return 1
}
oha() { podman run --rm --network $NET $OHA "$@"; }

row() { # name url body  (gateway already running)
  local name=$1 url=$2 body=$3
  local answer; answer=$(podman run --rm --network $NET $CURL -s -X POST -H 'content-type: application/json' -d "$body" "$url")
  echo "$name answers: ${answer:0:200}"
  case "$answer" in *errors*) echo "   the answer carries errors, not benchmarking"; return 1;; esac
  for r in $(seq 1 "$REPEATS"); do
    oha -z 10s -c "$CONCURRENCY" --no-tui -m POST -H 'content-type: application/json' -d "$body" "$url" >/dev/null 2>&1 || true
    oha -z "$DURATION" -c "$CONCURRENCY" --no-tui --output-format json -m POST -H 'content-type: application/json' -d "$body" "$url" > "$OUT/lattice-$name-$r.json"
    podman stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' lat-gateway > "$OUT/lattice-$name-$r.stats" 2>/dev/null || true
  done
}

lattice() { # name cache
  podman rm -f lat-gateway >/dev/null 2>&1 || true
  podman create --name lat-gateway --network $NET -e BACKEND=lat-backend:9090 -e ADDR=0.0.0.0:8888 -e CACHE="$2" -e RUST_LOG=error $UBUNTU sh -c "chmod +x /gw && exec /gw" >/dev/null
  podman cp "$LATTICE" lat-gateway:/gw; podman start lat-gateway >/dev/null
  up http://lat-gateway:8888/graphql "$GQL"; row "$1" http://lat-gateway:8888/graphql "$GQL"
}
shiguchi() { # name url body props
  podman rm -f lat-gateway >/dev/null 2>&1 || true
  podman create --name lat-gateway --network $NET -e PORT=8080 -e BACKEND_HOST=lat-backend -e BACKEND_PORT=9090 \
    -e GATEWAY_PROPS="$4" $TEMURIN java -Xmx512m -cp /jmh.jar dev.appiaworks.shiguchi.bench.ComparisonGateway >/dev/null
  podman cp "$JAR" lat-gateway:/jmh.jar; podman start lat-gateway >/dev/null
  up "$2" "$3"; row "$1" "$2" "$3"
}

# service 'orders' (DYNAMIC) discovers every service the backend reflects, greeter included
GQLPROPS='shiguchi.graphql.enabled=true'
lattice lattice-nocache off
lattice lattice-cache on
shiguchi shiguchi-gql http://lat-gateway:8080/graphql "$GQL" "$GQLPROPS"
shiguchi shiguchi-gql-doccache http://lat-gateway:8080/graphql "$GQL" "$GQLPROPS,shiguchi.graphql.document-cache.enabled=true"
shiguchi shiguchi-gql-respcache http://lat-gateway:8080/graphql "$GQL" "$GQLPROPS,shiguchi.graphql.response-cache.enabled=true"
shiguchi shiguchi-rest http://lat-gateway:8080/bench.Orders/Get "$REST" ""

python - "$OUT" <<'PY'
import json, glob, statistics, sys, re
out = sys.argv[1]
labels = [("lattice-nocache","1 Protocol-Lattice, cache off"),("lattice-cache","2 Protocol-Lattice, cache on"),
          ("shiguchi-gql","3 Shiguchi GraphQL"),("shiguchi-gql-doccache","4 Shiguchi GraphQL, document cache"),
          ("shiguchi-rest","5 Shiguchi REST (bench.Orders/Get)"),
          ("shiguchi-gql-respcache","6 Shiguchi GraphQL, response cache")]
print("\n%-38s %9s %8s %8s %8s %7s" % ("row","req/s","p50 ms","p99 ms","non-200","repeats"))
for key, label in labels:
    runs = [json.load(open(f)) for f in sorted(glob.glob(f"{out}/lattice-{key}-[0-9].json"))]
    if not runs: print("%-38s missing" % label); continue
    runs.sort(key=lambda r: r["summary"]["requestsPerSec"]); m = runs[len(runs)//2]
    lp = m.get("latencyPercentiles", {}); bad = sum(v for k, v in m.get("statusCodeDistribution", {}).items() if k != "200")
    print("%-38s %9.0f %8.2f %8.2f %8d %7d" % (label, m["summary"]["requestsPerSec"], lp.get("p50",0)*1000, lp.get("p99",0)*1000, bad, len(runs)))
PY
