#!/usr/bin/env bash
# Builds the Protocol-Lattice side of the GraphQL comparison inside WSL (Ubuntu 24.04, rustup
# stable, protoc 28.3 under ~/.local/protoc) and writes build/comparison/lattice-gateway.
#   comparison/lattice/build.sh /path/to/grpc_graphql_gateway-checkout
# Pinned commit: see PIN below; the script refuses another one unless ALLOW_OTHER=1.
set -euo pipefail
PIN=c37cdd9c603b94c8bcfd4fa755caf2dffb570cf3
SRC=${1:?path to a Protocol-Lattice/grpc_graphql_gateway checkout}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/../../build/comparison}
[ "$(git -C "$SRC" rev-parse HEAD)" = "$PIN" ] || [ "${ALLOW_OTHER:-}" = 1 ] || { echo "checkout is not $PIN"; exit 1; }
mkdir -p "$OUT"
WORK=$HOME/lattice-build; rm -rf "$WORK"; mkdir -p "$WORK"
git -C "$SRC" archive HEAD | tar -x -C "$WORK"
cp "$HERE/bench_gateway.rs" "$WORK/examples/bench_gateway.rs"
printf '\n[[bin]]\nname = "bench_gateway"\npath = "examples/bench_gateway.rs"\n' >> "$WORK/Cargo.toml"
. "$HOME/.cargo/env"
export PROTOC=$HOME/.local/protoc/bin/protoc PROTOC_INCLUDE=$HOME/.local/protoc/include
( cd "$WORK" && rustc -V > "$OUT/lattice-rustc.txt" && cargo build --release --bin bench_gateway )
cp "$WORK/target/release/bench_gateway" "$OUT/lattice-gateway"
echo "built $OUT/lattice-gateway ($(cat "$OUT/lattice-rustc.txt"))"
