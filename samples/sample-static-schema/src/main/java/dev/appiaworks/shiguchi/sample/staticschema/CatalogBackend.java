package dev.appiaworks.shiguchi.sample.staticschema;

import dev.appiaworks.shiguchi.sample.staticschema.proto.CatalogServiceGrpc;
import dev.appiaworks.shiguchi.sample.staticschema.proto.GetProductRequest;
import dev.appiaworks.shiguchi.sample.staticschema.proto.ListProductsRequest;
import dev.appiaworks.shiguchi.sample.staticschema.proto.Product;
import dev.appiaworks.shiguchi.sample.staticschema.proto.ProductList;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The catalog backend, written against the generated stubs — the ordinary way to implement a gRPC
 * service when you have the protos.
 *
 * <p>It is a plain {@code @Service}: Spring gRPC finds the {@code BindableService} bean and serves it on
 * {@code spring.grpc.server.port}. Note what is <em>not</em> here: reflection. Every other sample lets
 * the gateway ask the backend what it serves. This one deliberately does not
 * ({@code spring.grpc.server.reflection.enabled=false}), which is the whole point — the gateway has to
 * learn the schema some other way, and the two profiles show the two ways it can.
 *
 * <p>In a real deployment this is a separate process. It runs in-process here so the sample is one
 * module with nothing else to start.
 */
@Service
public class CatalogBackend extends CatalogServiceGrpc.CatalogServiceImplBase {

    private static final Map<String, Product> PRODUCTS = Map.of(
            "A100", product("A100", "Anvil, 50 kg", 129.0),
            "B200", product("B200", "Bird seed, 10 kg", 18.5),
            "C300", product("C300", "Catapult, portable", 349.99));

    private static Product product(String sku, String name, double price) {
        return Product.newBuilder()
                .setSku(sku)
                .setName(name)
                .setPrice(price)
                .build();
    }

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<Product> out) {
        Product product = PRODUCTS.get(request.getSku());
        if (product == null) {
            out.onError(Status.NOT_FOUND
                    .withDescription("no product " + request.getSku())
                    .asRuntimeException());
            return;
        }
        out.onNext(product);
        out.onCompleted();
    }

    @Override
    public void listProducts(ListProductsRequest request, StreamObserver<ProductList> out) {
        List<Product> sorted =
                PRODUCTS.keySet().stream().sorted().map(PRODUCTS::get).toList();
        out.onNext(ProductList.newBuilder().addAllProducts(sorted).build());
        out.onCompleted();
    }
}
