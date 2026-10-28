package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.protobuf.extras.serde.WireJsonTranscoder;
import dev.appiaworks.shiguchi.bench.proto.ListOrdersRequest;
import dev.appiaworks.shiguchi.bench.proto.OrderResponse;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.rest.GrpcRequestBuilder;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

/**
 * Server-streaming through {@link ReactiveGrpcDispatcher#dispatchStreaming} — the NDJSON path — with
 * the consumer's demand as the variable. The dispatcher disables gRPC's auto-request and drives
 * {@code request(n)} from downstream demand, so a consumer asking for one message at a time should
 * cost per-message round trips of demand, not buffered messages: the {@code gc.alloc.rate.norm}
 * column is the check that no batch of {@code messages} ever sits in the gateway, and the time
 * column is what that flow control costs against an unbounded drain.
 *
 * <ul>
 *   <li>{@code drainUnbounded} — {@code collectList}: request everything up front.
 *   <li>{@code demandOneAtATime} — {@code request(1)} per message, the slowest consumer shape.
 *   <li>{@code demandInBatchesOf16} — a consumer that reads ahead a little, what an HTTP writer
 *       with a small buffer looks like.
 * </ul>
 *
 * <p>Each message is the end-to-end fixture order transcoded by Tier 0 (dynamic-only service, no
 * message object materialised).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class StreamingBackpressureBenchmark {

    @Param({"100"})
    public int messages;

    private Server server;
    private ManagedChannel channel;
    private ReactiveGrpcDispatcher dispatcher;
    private RouteDefinition route;
    private ServiceEndpointContext ctx;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        OrderResponse order = BenchOrders.order(42_000_000_123L, 3);
        var method = io.grpc.MethodDescriptor.<ListOrdersRequest, OrderResponse>newBuilder()
                .setType(MethodType.SERVER_STREAMING)
                .setFullMethodName(BenchOrders.SERVICE + "/List")
                .setRequestMarshaller(ProtoUtils.marshaller(ListOrdersRequest.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(OrderResponse.getDefaultInstance()))
                .build();
        var service = ServerServiceDefinition.builder(BenchOrders.SERVICE)
                .addMethod(method, ServerCalls.asyncServerStreamingCall((req, obs) -> {
                    // Honours flow control: gRPC's ServerCalls only pushes while the client has
                    // requested, so a slow consumer stalls the server here, not in the gateway.
                    for (int i = 0; i < req.getCount(); i++) obs.onNext(order);
                    obs.onCompleted();
                }))
                .build();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        var props = new GatewayProperties();
        props.getClient().setDefaultDeadline(java.time.Duration.ZERO);
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        dispatcher = new ReactiveGrpcDispatcher(
                new GrpcRequestBuilder(), serde, new GrpcDescriptorCache(), new WireJsonTranscoder(), props);

        FileDescriptor file = BenchOrders.dynamicFile();
        ctx = BenchOrders.context("orders", file, channel);
        route = RouteDefinition.builder(BenchOrders.SERVICE, "List", MethodType.SERVER_STREAMING, "orders")
                .httpBinding("GET", "/v1/orders")
                .build();
        ctx.addRoute(route);

        if (drainUnbounded() != messages) throw new IllegalStateException("stream did not deliver every message");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private Flux<byte[]> stream() {
        return dispatcher.dispatchStreaming(
                route, ctx, Map.of(), Map.of("count", String.valueOf(messages)), null, null);
    }

    @Benchmark
    public int drainUnbounded() {
        return stream().collectList().block().size();
    }

    private static final org.springframework.core.io.buffer.DataBufferFactory POOLED =
            new org.springframework.core.io.buffer.NettyDataBufferFactory(
                    io.netty.buffer.PooledByteBufAllocator.DEFAULT);
    private static final byte[] NEWLINE = {10};
    private static final byte[] NONE = new byte[0];

    /** The framing the NDJSON writer used to do: each item's byte[] copied into a fresh pooled buffer, plus the newline. */
    @Benchmark
    public long drainFramedByCopy() {
        return stream()
                .map(json -> {
                    org.springframework.core.io.buffer.DataBuffer buffer = POOLED.allocateBuffer(json.length + 1);
                    buffer.write(json);
                    buffer.write(NEWLINE);
                    return buffer;
                })
                .reduce(0L, (total, buffer) -> {
                    long next = total + buffer.readableByteCount();
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    return next;
                })
                .block();
    }

    /** The framing it does now: each item transcoded from its wire bytes straight into its pooled buffer. */
    @Benchmark
    public long drainFramedDirect() {
        return dispatcher
                .dispatchStreamingFrames(
                        route,
                        ctx,
                        Map.of(),
                        Map.of("count", String.valueOf(messages)),
                        null,
                        null,
                        POOLED,
                        NONE,
                        NEWLINE,
                        512)
                .reduce(0L, (total, buffer) -> {
                    long next = total + buffer.readableByteCount();
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    return next;
                })
                .block();
    }

    @Benchmark
    public long demandOneAtATime() throws InterruptedException {
        return drainWithDemand(1);
    }

    @Benchmark
    public long demandInBatchesOf16() throws InterruptedException {
        return drainWithDemand(16);
    }

    /** Requests {@code batch} messages at a time and only asks again once they arrived. */
    private long drainWithDemand(int batch) throws InterruptedException {
        AtomicLong received = new AtomicLong();
        CountDownLatch done = new CountDownLatch(1);
        stream().subscribe(new BaseSubscriber<>() {
            private int outstanding;

            @Override
            protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
                outstanding = batch;
                request(batch);
            }

            @Override
            protected void hookOnNext(byte[] value) {
                received.incrementAndGet();
                if (--outstanding == 0) {
                    outstanding = batch;
                    request(batch);
                }
            }

            @Override
            protected void hookFinally(reactor.core.publisher.SignalType type) {
                done.countDown();
            }
        });
        done.await();
        return received.get();
    }
}
