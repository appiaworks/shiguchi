package dev.appiaworks.shiguchi.sample.validation;

import dev.appiaworks.shiguchi.sample.validation.proto.CreateOrderRequest;
import dev.appiaworks.shiguchi.sample.validation.proto.Order;
import dev.appiaworks.shiguchi.sample.validation.proto.OrderServiceGrpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The order backend, which deliberately does <em>no</em> validation of its own.
 *
 * <p>That is what makes the sample legible: every rejection you see came from the gateway, and
 * every request that reaches {@code createOrder} is one the gateway considered valid. A real
 * backend should still validate — the gateway is not the only way in — but then you could not
 * tell which layer refused a request.
 */
@Component
public class OrderBackend {

    private static final Logger log = LoggerFactory.getLogger(OrderBackend.class);

    private final int port;
    private final AtomicInteger sequence = new AtomicInteger();
    private Server server;

    public OrderBackend(@Value("${sample.backend-port:9090}") int port) {
        this.port = port;
    }

    @PostConstruct
    public void start() throws Exception {
        server = ServerBuilder.forPort(port)
                .addService(new OrderService())
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        log.info("order backend listening on :{} — it does not validate anything itself", port);
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.shutdownNow();
    }

    private final class OrderService extends OrderServiceGrpc.OrderServiceImplBase {
        @Override
        public void createOrder(CreateOrderRequest request, StreamObserver<Order> out) {
            log.info(
                    "backend reached: sku={} quantity={} email={}",
                    request.getSku(),
                    request.getQuantity(),
                    request.getCustomerEmail());
            out.onNext(Order.newBuilder()
                    .setId("ORD-" + sequence.incrementAndGet())
                    .setSku(request.getSku())
                    .setQuantity(request.getQuantity())
                    .setCustomerEmail(request.getCustomerEmail())
                    .build());
            out.onCompleted();
        }
    }
}
