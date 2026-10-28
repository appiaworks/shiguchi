# grpc-gateway (Go) in front of the comparison backend (comparison/compare.sh): the bench proto
# run through protoc-gen-grpc-gateway with generate_unbound_methods, so every method is served as
# POST /bench.Orders/Method with the request in the body — the same routes the other two map.
# Build context: grpc-gateway-benchmarks/.
FROM docker.io/golang:1.27 AS build
RUN apt-get update \
 && apt-get install -y --no-install-recommends protobuf-compiler libprotobuf-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY comparison/grpc-gateway-go/go.mod comparison/grpc-gateway-go/main.go ./
COPY src/jmh/proto/bench_e2e.proto proto/bench/bench_e2e.proto
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest \
 && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest \
 && go install github.com/grpc-ecosystem/grpc-gateway/v2/protoc-gen-grpc-gateway@latest
ENV PATH="/root/go/bin:${PATH}"
RUN mkdir -p gen \
 && protoc -I proto -I /usr/include \
      --go_out=gen --go_opt=paths=source_relative \
      --go_opt=Mbench/bench_e2e.proto=example.com/cmp/gen/bench \
      --go-grpc_out=gen --go-grpc_opt=paths=source_relative \
      --go-grpc_opt=Mbench/bench_e2e.proto=example.com/cmp/gen/bench \
      --grpc-gateway_out=gen --grpc-gateway_opt=paths=source_relative \
      --grpc-gateway_opt=generate_unbound_methods=true \
      --grpc-gateway_opt=Mbench/bench_e2e.proto=example.com/cmp/gen/bench \
      bench/bench_e2e.proto \
 && go mod tidy \
 && CGO_ENABLED=0 go build -o /gw .

FROM docker.io/alpine:3.24
COPY --from=build /gw /gw
ENTRYPOINT ["/gw"]
