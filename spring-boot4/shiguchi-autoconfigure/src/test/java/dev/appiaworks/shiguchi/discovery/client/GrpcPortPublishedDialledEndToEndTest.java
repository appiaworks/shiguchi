package dev.appiaworks.shiguchi.discovery.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.appinfo.ApplicationInfoManager;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.routing.DiscoveryNameResolverProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.protobuf.services.HealthStatusManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

/**
 * The backend publishes its bound gRPC port; the registry carries it next to the HTTP port; the
 * gateway's {@code discovery:///} resolver dials the gRPC one.
 */
@DisplayName("gRPC port published by the backend, dialled by the gateway")
class GrpcPortPublishedDialledEndToEndTest {

    @Test
    @DisplayName(
            "GIVEN a registry entry with an HTTP port and published grpc-port metadata WHEN dialled through discovery:/// SHOULD reach the gRPC server")
    void GIVEN_published_grpc_port_WHEN_dialled_SHOULD_reach_the_server() throws Exception {
        Server server = ServerBuilder.forPort(0)
                .addService(new HealthStatusManager().getHealthService())
                .build()
                .start();
        DiscoveryNameResolverProvider provider = null;
        ManagedChannel channel = null;
        try {
            var runner = new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(GrpcPortRegistrationAutoConfiguration.class))
                    .withUserConfiguration(GrpcPortRegistrationAutoConfigurationTest.EurekaBeans.class)
                    .withPropertyValues("shiguchi.discovery.client.expose-grpc-port=true")
                    .withBean(Server.class, () -> server);
            var holder = new java.util.concurrent.atomic.AtomicReference<java.util.Map<String, String>>();
            runner.run(ctx -> {
                ctx.getBean(GrpcPortRegistrationAutoConfiguration.GrpcPortReadyListener.class)
                        .onReady();
                holder.set(new java.util.HashMap<>(
                        ctx.getBean(ApplicationInfoManager.class).getInfo().getMetadata()));
            });
            // The registry entry: the HTTP port (nothing listens there) plus the published metadata.
            DefaultServiceInstance instance =
                    new DefaultServiceInstance("orders-1", "orders", "localhost", 1, false, holder.get());
            DiscoveryClient client = new DiscoveryClient() {
                @Override
                public String description() {
                    return "test";
                }

                @Override
                public List<ServiceInstance> getInstances(String serviceId) {
                    return "orders".equals(serviceId) ? List.of(instance) : List.of();
                }

                @Override
                public List<String> getServices() {
                    return List.of("orders");
                }
            };
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("client", client);
            ObjectProvider<DiscoveryClient> clients = beans.getBeanProvider(DiscoveryClient.class);
            GatewayProperties gateway = new GatewayProperties();
            gateway.getClient()
                    .setDiscoveryGrpcPort(dev.appiaworks.shiguchi.config.ClientConfig.DiscoveryGrpcPort.REQUIRE);
            provider = new DiscoveryNameResolverProvider(clients, gateway);
            channel = ManagedChannelBuilder.forTarget(provider.targetFor("orders"))
                    .usePlaintext()
                    .build();

            HealthCheckResponse response = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(10, java.util.concurrent.TimeUnit.SECONDS)
                    .check(HealthCheckRequest.getDefaultInstance());

            assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
        } finally {
            if (channel != null) channel.shutdownNow();
            if (provider != null) provider.destroy();
            server.shutdownNow();
        }
    }
}
