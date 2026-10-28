package dev.appiaworks.shiguchi.sample.merge;

import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-process order backend, served over real gRPC with reflection enabled so the gateway
 * discovers it the same way it would any deployed service.
 *
 * @param sorted whether this backend honours {@code sort_fields}. The whole sample turns on this
 *     flag: a sorted backend lets the gateway push the sort down and stream, an unsorted one
 *     forces the gateway to materialise and sort in memory.
 */
public class OrderServiceServer {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceServer.class);

    private final int port;
    private final String region;
    private final boolean sorted;
    private final List<DynamicMessage> rows;
    private Server server;

    public OrderServiceServer(int port, String region, boolean sorted, List<DynamicMessage> rows) {
        this.port = port;
        this.region = region;
        this.sorted = sorted;
        this.rows = rows;
    }

    public void start() throws IOException {
        var method = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                .setFullMethodName("sample.merge.OrderService/listOrders")
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.newBuilder(OrderBackend.LIST_REQUEST)
                        .build()))
                .setResponseMarshaller(ProtoUtils.marshaller(
                        DynamicMessage.newBuilder(OrderBackend.ORDER).build()))
                .build();

        // The schema descriptor is what ProtoReflectionService serves. Without it the gateway can
        // see the service exists but not its messages or its google.api.http binding, so it would
        // register the context with zero routes.
        var service = ServerServiceDefinition.builder(io.grpc.ServiceDescriptor.newBuilder("sample.merge.OrderService")
                        .setSchemaDescriptor(new OrderServiceSchema())
                        .addMethod(method)
                        .build())
                .addMethod(method, ServerCalls.asyncServerStreamingCall(this::listOrders))
                .build();

        server = ServerBuilder.forPort(port)
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();

        log.info(
                "{} backend listening on :{} ({})",
                region,
                port,
                sorted ? "honours sort_fields — gateway streams it" : "ignores sort_fields — gateway materialises it");
    }

    private void listOrders(DynamicMessage request, StreamObserver<DynamicMessage> out) {
        List<DynamicMessage> result = new ArrayList<>(rows);

        if (sorted) {
            // Honour the sort the gateway pushed down. This is the one thing a backend has to do
            // to let the gateway stream it instead of buffering it whole.
            Comparator<DynamicMessage> comparator = comparatorFromRequest(request);
            if (comparator != null) {
                result.sort(comparator);
            }
            int pageSize = pageSizeFromRequest(request);
            if (pageSize > 0 && result.size() > pageSize) {
                result = result.subList(0, pageSize);
            }
        }
        // The unsorted backend deliberately ignores both sort_fields and page_request, returning
        // rows in insertion order — exactly what a service that was never taught to sort does.

        for (DynamicMessage row : result) {
            out.onNext(row);
        }
        out.onCompleted();
    }

    private Comparator<DynamicMessage> comparatorFromRequest(DynamicMessage request) {
        var sortFieldsFd = OrderBackend.LIST_REQUEST.findFieldByName("sort_fields");
        int count = request.getRepeatedFieldCount(sortFieldsFd);
        if (count == 0) {
            return null;
        }
        Comparator<DynamicMessage> comparator = null;
        for (int i = 0; i < count; i++) {
            DynamicMessage sortField = (DynamicMessage) request.getRepeatedField(sortFieldsFd, i);
            var desc = sortField.getDescriptorForType();
            String field = String.valueOf(sortField.getField(desc.findFieldByName("field")));
            String order = String.valueOf(sortField.getField(desc.findFieldByName("sort_order")));
            var next = OrderBackend.comparatorFor(field, !"SORT_ORDER_DESC".equals(order));
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }

    private int pageSizeFromRequest(DynamicMessage request) {
        var pageRequestFd = OrderBackend.LIST_REQUEST.findFieldByName("page_request");
        if (!request.hasField(pageRequestFd)) {
            return 0;
        }
        DynamicMessage pageRequest = (DynamicMessage) request.getField(pageRequestFd);
        var sizeFd = pageRequest.getDescriptorForType().findFieldByName("page_size");
        return ((Number) pageRequest.getField(sizeFd)).intValue();
    }

    /** Exposes the file descriptor to gRPC reflection. */
    private static final class OrderServiceSchema
            implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
        @Override
        public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
            return OrderBackend.FILE;
        }

        @Override
        public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
            return OrderBackend.FILE.findServiceByName("OrderService");
        }
    }

    public void stop() {
        if (server != null) {
            server.shutdownNow();
        }
    }
}
