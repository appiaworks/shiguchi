#!/usr/bin/env sh
# Keeps a sample gateway busy so the dashboard has something to draw.
#
#   ./load.sh                                  # sample-discovery-gateway's routes, forever
#   ./load.sh http://localhost:8080 /v1/products/A100   # any base URL, any routes
#   RPS=50 ./load.sh                           # faster
#
# One in ten requests asks for an id that does not exist, so the failed-calls and
# HTTP-status panels have a NOT_FOUND / 404 series to show. Stop with Ctrl-C.
set -eu

BASE="${1:-http://localhost:8080}"
shift 2>/dev/null || true
if [ "$#" -gt 0 ]; then
  ROUTES="$*"
else
  ROUTES="/v1/products /v1/products/A100"
fi
RPS="${RPS:-10}"
PAUSE=$(awk "BEGIN { printf \"%.3f\", 1 / $RPS }")

echo "load: $RPS req/s against $BASE ($ROUTES), plus a 404 every tenth request"
i=0
while :; do
  for route in $ROUTES; do
    i=$((i + 1))
    if [ $((i % 10)) -eq 0 ]; then
      curl -s -o /dev/null "$BASE/v1/products/does-not-exist-$i" || true
    else
      curl -s -o /dev/null "$BASE$route" || true
    fi
    sleep "$PAUSE"
  done
done
