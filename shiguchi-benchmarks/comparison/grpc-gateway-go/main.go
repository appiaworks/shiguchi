// grpc-gateway (Go) for the side-by-side comparison: one ServeMux, the generated bench.Orders
// handler dialled to the backend, plain HTTP/1.1 in front. No logging on the request path.
package main

import (
	"context"
	"log"
	"net/http"
	"os"

	bench "example.com/cmp/gen/bench"
	"github.com/grpc-ecosystem/grpc-gateway/v2/runtime"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	backend := env("BACKEND", "cmp-backend:9090")
	port := env("PORT", "8082")
	mux := runtime.NewServeMux()
	opts := []grpc.DialOption{grpc.WithTransportCredentials(insecure.NewCredentials())}
	if err := bench.RegisterOrdersHandlerFromEndpoint(context.Background(), mux, backend, opts); err != nil {
		log.Fatal(err)
	}
	log.Printf("grpc-gateway on :%s -> %s", port, backend)
	log.Fatal(http.ListenAndServe(":"+port, mux))
}
