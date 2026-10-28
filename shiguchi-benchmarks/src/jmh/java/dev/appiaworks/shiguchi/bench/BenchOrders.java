package dev.appiaworks.shiguchi.bench;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.shiguchi.bench.proto.Item;
import dev.appiaworks.shiguchi.bench.proto.OrderResponse;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicSerializerFactory;
import io.grpc.ManagedChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared fixture for the benchmarks that route through the gateway's dispatchers against the
 * compiled {@code bench.Orders} service: the same order the end-to-end benchmark uses, and a
 * {@link ServiceEndpointContext} built the way the route loaders build one — the fixture file
 * rebuilt as a dynamic descriptor (what reflection would serve), every method indexed, Tier 2
 * serializers compiled for every message.
 */
final class BenchOrders {

    private BenchOrders() {}

    static final String SERVICE = "bench.Orders";

    /** The fixture file as a dynamic descriptor, as a reflection-served service would present it. */
    static FileDescriptor dynamicFile() throws Exception {
        return FileDescriptor.buildFrom(
                OrderResponse.getDescriptor().getFile().toProto(),
                new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
    }

    static ServiceEndpointContext context(String name, FileDescriptor file, ManagedChannel channel) throws Exception {
        var config = new dev.appiaworks.shiguchi.config.ServiceDefinition();
        config.setName(name);
        var ctx = new ServiceEndpointContext(config, channel);
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        ServiceDescriptor service = file.findServiceByName("Orders");
        ctx.setServiceByFqn(Map.of(service.getFullName(), service));
        Map<String, MethodDescriptor> byKey = new HashMap<>();
        for (MethodDescriptor md : service.getMethods()) byKey.put(service.getFullName() + "/" + md.getName(), md);
        ctx.setMethodByKey(byKey);
        JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
        var factory = new DynamicSerializerFactory();
        for (Descriptor d : file.getMessageTypes()) {
            types.add(d);
            ctx.putSerializer(d, factory.compileDescriptor(d));
        }
        ctx.setTypeRegistry(types.build());
        return ctx;
    }

    /** The end-to-end fixture order: 6 scalars, {@code items} nested items, a 3-entry map, a Timestamp. */
    static OrderResponse order(long id, int items) {
        OrderResponse.Builder b = OrderResponse.newBuilder()
                .setId(id)
                .setName("benchmark-order-" + id)
                .setActive(true)
                .setScore(12.75d)
                .setCount(314)
                .setNote("a moderately sized note field for realism")
                .setCreatedAt(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(123_456_789));
        for (int i = 0; i < items; i++) {
            b.addItems(Item.newBuilder()
                    .setSku("sku-" + i)
                    .setQty(i + 1)
                    .setPrice(9.99d * (i + 1))
                    .setNote("item note " + i)
                    .setFlag(i % 2 == 0));
        }
        for (int i = 0; i < 3; i++) b.putLabels("k" + i, "v" + i);
        return b.build();
    }
}
