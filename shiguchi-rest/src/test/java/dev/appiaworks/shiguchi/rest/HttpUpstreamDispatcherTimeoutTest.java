package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import dev.appiaworks.shiguchi.routing.HttpOperationBinding;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import dev.appiaworks.shiguchi.serde.DynamicProtoSerde;
import dev.appiaworks.shiguchi.serde.SerdeSelector;
import dev.appiaworks.shiguchi.serde.StaticSerdeDetector;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The GraphQL / merge dispatch path times out at the timeout resolved for the operation: the
 * route's own value when the loader resolved one, else the service-level resolution
 * (deadline, default-deadline, {@code client.http-timeout}).
 */
@DisplayName("HttpUpstreamDispatcher timeout tests")
class HttpUpstreamDispatcherTimeoutTest {

    private static final String PKG = "test.timeout";
    private static final Duration SLOW = Duration.ofSeconds(3);

    private static DisposableServer slowStub;

    @BeforeAll
    static void startStub() {
        slowStub = HttpServer.create()
                .port(0)
                .route(r -> r.get(
                        "/ping",
                        (req, res) -> Mono.delay(SLOW)
                                .then(res.header("Content-Type", "application/json")
                                        .sendString(Mono.just("{\"name\":\"late\"}"))
                                        .then())))
                .bindNow();
    }

    @AfterAll
    static void stopStub() {
        slowStub.disposeNow();
    }

    @Test
    @DisplayName("GIVEN a route with a 300ms upstream timeout WHEN the backend takes 3s SHOULD fail DEADLINE_EXCEEDED")
    void given_routeTimeout_when_backendIsSlow_should_failDeadlineExceeded() throws Exception {
        GatewayProperties props = new GatewayProperties();
        props.getClient().setHttpTimeout(Duration.ofSeconds(30));
        ServiceDefinition svc = new ServiceDefinition();
        svc.setName("slow");
        RouteDefinition route = route(Duration.ofMillis(300));
        ServiceEndpointContext ctx = context(svc, route);

        long started = System.nanoTime();
        assertDeadlineExceeded(props, route, ctx);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SLOW);
    }

    @Test
    @DisplayName(
            "GIVEN a route without a resolved timeout WHEN client.http-timeout is 300ms SHOULD fail DEADLINE_EXCEEDED")
    void given_noRouteTimeout_when_httpTimeoutIsShort_should_failDeadlineExceeded() throws Exception {
        GatewayProperties props = new GatewayProperties();
        props.getClient().setHttpTimeout(Duration.ofMillis(300));
        ServiceDefinition svc = new ServiceDefinition();
        svc.setName("slow");
        RouteDefinition route = route(null);
        ServiceEndpointContext ctx = context(svc, route);

        long started = System.nanoTime();
        assertDeadlineExceeded(props, route, ctx);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SLOW);
    }

    @Test
    @DisplayName(
            "GIVEN a zero service deadline and a 300ms http-timeout WHEN the backend is slow SHOULD still time out")
    void given_zeroDeadline_when_httpTimeoutIsShort_should_stillTimeOut() throws Exception {
        GatewayProperties props = new GatewayProperties();
        props.getClient().setDefaultDeadline(Duration.ZERO);
        props.getClient().setHttpTimeout(Duration.ofMillis(300));
        ServiceDefinition svc = new ServiceDefinition();
        svc.setName("slow");
        svc.setDeadline(Duration.ZERO);
        RouteDefinition route = route(null);
        ServiceEndpointContext ctx = context(svc, route);

        assertDeadlineExceeded(props, route, ctx);
    }

    private static void assertDeadlineExceeded(
            GatewayProperties props, RouteDefinition route, ServiceEndpointContext ctx) {
        var serde = new SerdeSelector(new StaticSerdeDetector(), new DynamicProtoSerde(new GrpcDescriptorCache()));
        var dispatcher = new HttpUpstreamDispatcher(serde, props, Optional.empty(), List.of());
        assertThatThrownBy(() -> dispatcher
                        .dispatchMessage(route, ctx, Map.of(), new Metadata())
                        .block(Duration.ofSeconds(20)))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }

    private static RouteDefinition route(Duration timeout) {
        String baseUrl = "http://localhost:" + slowStub.port();
        return RouteDefinition.builder(PKG + ".Svc", "ping", MethodType.UNARY, baseUrl)
                .httpUpstream(baseUrl)
                .httpBinding("GET", "/ping")
                .upstreamTimeout(timeout)
                .build();
    }

    private static ServiceEndpointContext context(ServiceDefinition svc, RouteDefinition route) throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("timeout.proto")
                .setPackage(PKG)
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder().setName("Req"))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Pong")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("name")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Svc")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("ping")
                                .setInputType("." + PKG + ".Req")
                                .setOutputType("." + PKG + ".Pong")))
                .build();
        FileDescriptor file = FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        var service = file.findServiceByName("Svc");
        var ctx = new ServiceEndpointContext(svc, null);
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        ctx.setServiceByFqn(Map.of(service.getFullName(), service));
        ctx.setMethodByKey(Map.of(service.getFullName() + "/ping", service.findMethodByName("ping")));
        ctx.putHttpBinding("ping", new HttpOperationBinding("GET", "/ping", List.of(), List.of(), false, false, false));
        ctx.addRoute(route);
        return ctx;
    }
}
