#!/usr/bin/env bash
# Side by side: this gateway, Envoy's gRPC-JSON transcoder and grpc-gateway (Go) in front of the
# same bench.Orders backend, the same POST /bench.Orders/Get with {"id":"order-1"}, the same load
# generator (oha), all as containers on one podman network. Numbers land in build/comparison/.
#
#   ./gradlew :shiguchi-benchmarks:jmhJar && shiguchi-benchmarks/comparison/compare.sh
#
# DURATION (default 30s) and CONCURRENCY (default 32) shape the measured run; a 10 s warm-up at
# the same concurrency precedes each. Needs podman (a running machine on Windows/macOS) and
# network access for the images and the Go module downloads.
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=$(ls -t build/libs/shiguchi-benchmarks-*-jmh.jar | head -1)  # newest: older versions linger in build/libs
DURATION=${DURATION:-30s}
CONCURRENCY=${CONCURRENCY:-32}
NET=gwcmp
OUT=build/comparison
TEMURIN=docker.io/eclipse-temurin:25-jre
ENVOY=docker.io/envoyproxy/envoy:v1.34.1
OHA=ghcr.io/hatoo/oha:latest
CURL=docker.io/curlimages/curl:latest
export MSYS_NO_PATHCONV=1

cleanup() {
  podman rm -f cmp-backend cmp-gateway cmp-envoy cmp-gogw >/dev/null 2>&1 || true
  podman network rm -f $NET >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup
mkdir -p "$OUT"
podman network create $NET >/dev/null

echo "== backend (bench.Orders, reflection, descriptor set)"
podman create --name cmp-backend --network $NET -e PORT=9090 $TEMURIN \
  java -Xmx256m -cp /jmh.jar dev.appiaworks.shiguchi.bench.ComparisonBackend /orders.pb >/dev/null
podman cp "$JAR" cmp-backend:/jmh.jar
podman start cmp-backend >/dev/null
for i in $(seq 1 60); do
  if podman logs cmp-backend 2>&1 | grep -q "serving on"; then break; fi
  sleep 1
done
podman cp cmp-backend:/orders.pb "$OUT/orders.pb"

echo "== this gateway"
podman create --name cmp-gateway --network $NET -e PORT=8080 -e BACKEND_HOST=cmp-backend -e BACKEND_PORT=9090 \
  -e GATEWAY_PROPS="${GATEWAY_PROPS:-}" $TEMURIN \
  java -Xmx512m -cp /jmh.jar dev.appiaworks.shiguchi.bench.ComparisonGateway >/dev/null
podman cp "$JAR" cmp-gateway:/jmh.jar
podman start cmp-gateway >/dev/null

echo "== envoy"
podman create --name cmp-envoy --network $NET $ENVOY envoy -c /etc/envoy/envoy.yaml --log-level warn >/dev/null
podman cp comparison/envoy.yaml cmp-envoy:/etc/envoy/envoy.yaml
podman cp "$OUT/orders.pb" cmp-envoy:/etc/envoy/orders.pb
podman start cmp-envoy >/dev/null

echo "== grpc-gateway (Go): building the image"
podman build -q -t cmp-gogw -f comparison/grpc-gateway-go/Dockerfile . >/dev/null
podman run -d --name cmp-gogw --network $NET -e PORT=8082 -e BACKEND=cmp-backend:9090 cmp-gogw >/dev/null

BODY='{"id":"order-1"}'
wait_for() { # name url
  for i in $(seq 1 90); do
    if podman run --rm --network $NET $CURL -s -o /dev/null -w '%{http_code}' -X POST -H 'content-type: application/json' -d "$BODY" "$2" 2>/dev/null | grep -q 200; then
      echo "   $1 up"; return 0
    fi
    sleep 1
  done
  echo "   $1 did not come up"; podman logs "$3" 2>&1 | tail -20; return 1
}
wait_for "this gateway" http://cmp-gateway:8080/bench.Orders/Get cmp-gateway
wait_for "envoy" http://cmp-envoy:8081/bench.Orders/Get cmp-envoy
wait_for "grpc-gateway (Go)" http://cmp-gogw:8082/bench.Orders/Get cmp-gogw

echo "== the answers (same order, three framings)"
for t in "gateway http://cmp-gateway:8080" "envoy http://cmp-envoy:8081" "gogw http://cmp-gogw:8082"; do
  set -- $t
  printf '%-8s ' "$1"
  podman run --rm --network $NET $CURL -s -X POST -H 'content-type: application/json' -d "$BODY" "$2/bench.Orders/Get" | head -c 160; echo
done

load() { # name url
  podman run --rm --network $NET $OHA -z 10s -c "$CONCURRENCY" --no-tui -m POST -H 'content-type: application/json' -d "$BODY" "$2" >/dev/null 2>&1 || true
  podman run --rm --network $NET $OHA -z "$DURATION" -c "$CONCURRENCY" --no-tui --output-format json \
    -m POST -H 'content-type: application/json' -d "$BODY" "$2" > "$OUT/$1.json"
}
echo "== load: $DURATION at concurrency $CONCURRENCY, after a 10 s warm-up each"
load gateway http://cmp-gateway:8080/bench.Orders/Get
load envoy http://cmp-envoy:8081/bench.Orders/Get
load gogw http://cmp-gogw:8082/bench.Orders/Get

python - "$OUT" <<'EOF'
import json, sys, os
out = sys.argv[1]
print("\n%-22s %10s %9s %9s %9s %8s" % ("proxy", "req/s", "p50 ms", "p90 ms", "p99 ms", "non-200"))
for name, label in [("gateway", "this gateway (JVM)"), ("envoy", "Envoy transcoder"), ("gogw", "grpc-gateway (Go)")]:
    p = os.path.join(out, name + ".json")
    if not os.path.exists(p):
        print("%-22s missing" % label); continue
    r = json.load(open(p))
    s = r["summary"]; lp = r.get("latencyPercentiles", {})
    codes = r.get("statusCodeDistribution", {})
    bad = sum(v for k, v in codes.items() if k != "200")
    ms = lambda k: lp.get(k, 0) * 1000
    print("%-22s %10.0f %9.2f %9.2f %9.2f %8d" % (label, s["requestsPerSec"], ms("p50"), ms("p90"), ms("p99"), bad))
EOF
