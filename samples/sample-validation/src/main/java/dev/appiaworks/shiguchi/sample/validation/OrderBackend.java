package dev.appiaworks.shiguchi.sample.validation;

import dev.appiaworks.shiguchi.sample.validation.proto.CreateOrderRequest;
import dev.appiaworks.shiguchi.sample.validation.proto.Order;
import dev.appiaworks.shiguchi.sample.validation.proto.OrderServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The order backend, which deliberately does <em>no</em> validation of its own.
 *
 * <p>That is what makes the sample legible: every rejection you see came from the gateway, and
 * every request that reaches {@code createOrder} is one the gateway considered valid. A real
 * backend should still validate — the gateway is not the only way in — but then you could not
 * tell which layer refused a request.
 *
 * <p>A plain {@code @Service}: Spring gRPC serves it on {@code spring.grpc.server.port}, with
 * reflection on by default so the gateway can read the schema.
 */
@Service
public class OrderBackend extends OrderServiceGrpc.OrderServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OrderBackend.class);

    private final AtomicInteger sequence = new AtomicInteger();

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
