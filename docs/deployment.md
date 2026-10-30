# Deployment

The Helm chart and the `sample-full` OCI image are published on every tagged release (see
[README → Deploy](../README.md#deploy)). This page covers the runtime side: which JVM the image
runs, how to get your own compiled stubs into it, and how the chart tunes it.

## Runtime

The image is `eclipse-temurin:25-jre` running the boot jar. The libraries are compiled for Java 21,
so an application of your own can run them on 21, but JDK 25 is what CI tests on and what the image
ships: it brings compact object headers, generational-only ZGC and the AOT cache, none of which 21
has.

## Image layout and AOT cache

The image's Dockerfile unpacks the boot jar into Spring Boot's layers (`dependencies`,
`spring-boot-loader`, `snapshot-dependencies`, `application`). Dependencies change far less often
than the application, so pulling a new release mostly fetches the small top layers, and running
unpacked skips the nested-jar class loader.

It also builds a JDK 25 AOT cache (JEP 483, 514, 515). A training run during the image build starts
the application context and records the classes it loaded and linked and the method profiles;
at startup the JVM maps those in instead of repeating the work. On `sample-full`, startup to a
refreshed context goes from ~3.3 s to ~1.8 s with the chart's default flags. That shortens
rollouts and HPA scale-out. The cache adds ~125 MB to the image.

The JVM uses the cache only when some settings match the training run. On a mismatch it logs
`Unable to use AOT cache` and starts normally, just without the speed-up:

- compact object headers on (the chart's default `jvm.options` keeps them),
- compressed oops on, which holds for any heap under 32 GB,
- G1, Parallel or Serial. ZGC does not use compressed oops on JDK 25, so `jvm.gc: Z` runs
  without the cache.

## Your compiled stubs in the image (Tier 1)

A service in `DYNAMIC` mode already serves REST responses on Tier 0, which reads the wire bytes
straight into JSON with no message object and measures as the fastest tier (see
[transcoding.md](transcoding.md#performance-by-discovery-mode)); it needs no classes, so a
backend's proto change never needs a gateway rebuild. A `STATIC` service runs Tier 0 too. Its
compiled gRPC stub classes on the gateway's classpath (see
[transcoding.md](transcoding.md#the-serde-tiers)) serve what Tier 0 cannot — a reply carrying
`google.protobuf.Any` or a group, a paginated unary reply whose headers need the decoded
message — through the generated parser and a writer over the stub's typed getters, and give
application code and `ProtoJson` the typed classes themselves. Choose `STATIC` for those, not
for response speed.

The stub jar is the one your protos compile to: `protoc` with `--java_out` and `--grpc-java_out`,
or the `com.google.protobuf` Gradle plugin, published by the team that owns the service. Build
it with [`protoc-gen-java-extras`](protoc-java-extras.md#json) and the classes carry their JSON
writer, so Tier 1 works on any Java; without the plugin it needs Java 25, which the image has.
Either way the stub must describe the schema the backend serves: the gateway compares the
class's descriptor with the service's field for field and leaves a type that differs on Tier 0.

Two ways to get the jar in:

**Build your own gateway application.** The surest route when you run a gateway for long: a
Boot application on the starters, with the stub jar as an ordinary dependency, packaged with the
same Dockerfile pattern as `sample-full` (`samples/sample-full/Dockerfile`):

```kotlin
dependencies {
    implementation("io.github.appiaworks:shiguchi-spring-boot3-starter:0.14.1")
    implementation("com.acme:orders-stubs:1.4.0")   // the *Grpc classes and their messages
}
```

**Derive from the published image.** The image runs the boot jar unpacked: `/app/app.jar` holds
the application and names every library under `/app/lib/` in its manifest `Class-Path`. A jar
copied next to them is not picked up by `java -jar`, which ignores every other classpath entry,
so the derived image starts with `-cp` instead and names the main class:

```dockerfile
FROM ghcr.io/appiaworks/shiguchi/sample-full:v0.14.1
COPY orders-stubs-1.4.0.jar /app/stubs/
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-cp", "app.jar:stubs/*", \
            "dev.appiaworks.shiguchi.sample.SampleGatewayApplication"]
```

`app.jar` on the classpath still brings `lib/*.jar` through its manifest, so nothing else
changes. The AOT cache stays usable: the JVM accepts a runtime classpath that extends the
training run's, and if it ever refuses it logs `Unable to use AOT cache` and starts normally.
A stub jar that needs libraries the image lacks (anything beyond `protobuf-java` and
`grpc-stub`, which are in `lib/`) gets them copied into `stubs/` too. With the Helm chart, point
`image.repository` and `image.tag` at the derived image; `extraVolumes` can mount a jar, but the
chart does not override the entrypoint, so a mounted jar is not on the classpath.

Then declare the service in `STATIC` mode, naming its `*Grpc` outer classes or letting the
gateway scan for them:

```yaml
shiguchi:
  services:
    - name: orders
      host: orders
      port: 9090
      mode: STATIC
      static-service-classes:
        - com.acme.orders.v1.OrderServiceGrpc
      # or: auto-discover: { classpath: true, scan-packages: [com.acme.orders] }
```

To see which types made it onto Tier 1, log `dev.appiaworks.shiguchi.serde.StaticSerdeDetector` at
DEBUG: `Tier1 serde registered: <type> -> <class>` per type, or a `Tier1 serde skipped` line
with the reason (schema differs, reaches `google.protobuf.Any`). One INFO line,
`Tier 1 serde off`, means no writer can be generated on this JVM and the stubs carry none.

## JVM tuning

The chart renders `.Values.jvm` into `JAVA_TOOL_OPTIONS`. The defaults are tuned for the chart's
default resources (`500m` CPU, `512Mi` memory) and for a reactive proxy, where the cost that
matters is request latency, not batch throughput:

| Value | Default | Flag | Why |
|---|---|---|---|
| `jvm.gc` | `G1` | `-XX:+UseG1GC` | Below 2 CPUs or ~1.8 GB the JVM picks SerialGC by itself, and SerialGC stops every request for each collection. G1 collects the young generation in parallel with short, bounded pauses. |
| `jvm.maxRamPercentage` | `65` | `-XX:MaxRAMPercentage=65` | Netty keeps request and response buffers off-heap. At 512Mi, a 75% heap leaves ~128Mi for direct buffers, metaspace, the code cache and thread stacks, and the kernel OOMKills the pod when they outgrow it. |
| `jvm.activeProcessorCount` | `2` | `-XX:ActiveProcessorCount=2` | A `500m` limit reads as one CPU, so the JVM sizes GC threads, Netty's event loops (2 × CPUs) and the common pool for a single core. Set it to the CPU limit rounded up, and never below 2. |
| `jvm.options` | see below | | |
| `jvm.extraOptions` | `""` | | Appended after `options`, so a flag can be added without restating the defaults. |

`jvm.options` holds:

- `-XX:+UseCompactObjectHeaders` (JDK 25, JEP 519): object headers shrink from 12 to 8 bytes.
  A gateway allocates many small objects per request (protobuf messages, JSON nodes, buffers), so
  the live heap and the allocation rate typically drop by 10–20% and young collections come less
  often. It works with every collector.
- `-XX:+ExitOnOutOfMemoryError`: a JVM that hit an `OutOfMemoryError` is left in an undefined
  state. Exiting lets Kubernetes restart the pod instead of leaving it half-working and still
  passing its probes.
- `-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`: `Schedulers.boundedElastic()`
  runs on virtual threads. The gateway itself never blocks, but a `GatewayAuthorizer` that calls a
  blocking client is told to use that scheduler. A blocked call then parks a virtual thread instead
  of holding a platform thread and its stack. The scheduler still runs at most 10 × CPUs tasks at
  once; with virtual threads that cap is cheap to raise
  (`-Dreactor.schedulers.defaultBoundedElasticSize=200` in `extraOptions`) when a slow policy
  engine makes requests queue behind it.

Set any field to `""` or `null` to drop its flag and let the JVM decide:

```yaml
jvm:
  activeProcessorCount: 4          # pod limit is 4 CPUs
  extraOptions: "-Xlog:gc:stderr"  # log every collection
```

### Virtual threads

`spring.threads.virtual.enabled` is not needed. It moves Tomcat's request threads, `@Async` and
`@Scheduled` onto virtual threads. The gateway runs on WebFlux and Netty with async gRPC stubs, so
no request holds a thread while it waits on a backend, and the property has nothing on the request
path to change. The Reactor property above covers the one place where blocking code is expected.

### Bigger heap

More heap makes young collections less frequent. It does not make them shorter: the time of a G1
young pause follows the amount of live data, not the heap size. Raise the memory limit when the
metrics show one of these:

- `jvm_gc_pause_seconds_count` rising steadily with traffic: several young collections a second.
- `jvm_memory_used_bytes{area="heap"}` staying near `jvm_memory_max_bytes` after collections.
  That is live data (large responses, merge fan-out, big descriptor sets) that no longer fits.
- `container_memory_working_set_bytes` close to the limit while the heap is not. Lower
  `maxRamPercentage` instead: the pressure is off-heap.

Raise `resources.limits.memory` and keep the percentage. The off-heap share grows with it.

### ZGC

`jvm.gc: Z` switches to ZGC, which is generational only on JDK 25. Its pauses stay under a
millisecond whatever the heap size, because it moves objects while the application runs. It pays
for that with CPU, memory and startup time (it runs without the
[AOT cache](#image-layout-and-aot-cache)): concurrent GC threads run alongside Netty's event loops, and the heap
needs headroom for the objects allocated during a cycle.

It is not worth it at the chart's default resources. With half a CPU, the concurrent GC threads
compete with the event loops, and G1's young pauses on a heap this small are a few milliseconds
already. Measure it when all of these hold:

- the pod has at least 2 CPUs (4 is better) and 2Gi of memory,
- the latency target is on p99 or p99.9 in single-digit milliseconds,
- `jvm_gc_pause_seconds_max` under G1 is a visible part of that p99.

Raise the resources first, then switch the collector, then compare the p99 before and after:

```yaml
resources:
  limits:
    cpu: "4"
    memory: 2Gi
jvm:
  gc: Z
  activeProcessorCount: 4
  maxRamPercentage: 70
```

ZGC's concurrent cycles do not show up in `jvm_gc_pause_seconds`; watch
`jvm_gc_concurrent_phase_time_seconds` and the pod's CPU instead.
