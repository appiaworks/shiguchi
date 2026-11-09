// Protocol-Lattice/grpc_graphql_gateway as the GraphQL side-by-side runs it: their greeter
// descriptors, our backend (greeter.Greeter/SayHello served by ComparisonBackend), no
// example middleware (the stock example rate-limits to 10 req/s). CACHE=on adds the in-memory
// response cache; the rest is HighPerfConfig::ultra_fast(), their recommended performance mode.
use anyhow::Result;
use grpc_graphql_gateway::{CacheConfig, Gateway, GrpcClient, HighPerfConfig};
use std::time::Duration;

const DESCRIPTORS: &[u8] = include_bytes!("../src/generated/greeter_descriptor.bin");

#[tokio::main]
async fn main() -> Result<()> {
    let backend = std::env::var("BACKEND").unwrap_or_else(|_| "127.0.0.1:9090".into());
    let addr = std::env::var("ADDR").unwrap_or_else(|_| "0.0.0.0:8888".into());
    let cache = std::env::var("CACHE").map(|v| v == "on").unwrap_or(false);

    let client = GrpcClient::builder(format!("http://{backend}"))
        .lazy(false)
        .connect()
        .await?;
    let mut builder = Gateway::builder()
        .with_descriptor_set_bytes(DESCRIPTORS)
        .add_grpc_client("greeter.Greeter", client)
        .with_high_performance(HighPerfConfig::ultra_fast());
    if cache {
        builder = builder.with_response_cache(CacheConfig {
            max_size: 10000,
            default_ttl: Duration::from_secs(3600),
            stale_while_revalidate: Some(Duration::from_secs(30)),
            invalidate_on_mutation: true,
            redis_url: None,
            vary_headers: vec!["Authorization".to_string()],
            smart_ttl_manager: None,
        });
    }
    builder.serve(addr).await?;
    Ok(())
}
