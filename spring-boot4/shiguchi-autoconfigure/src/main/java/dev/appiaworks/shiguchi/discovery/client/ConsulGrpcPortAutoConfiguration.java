package dev.appiaworks.shiguchi.discovery.client;

import io.grpc.Server;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.consul.ConsulClient;
import org.springframework.cloud.consul.discovery.ConsulDiscoveryProperties;
import org.springframework.cloud.consul.discovery.HeartbeatProperties;
import org.springframework.cloud.consul.serviceregistry.ConsulAutoServiceRegistration;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistrationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Runs in a <em>backend</em> app (not the gateway) and publishes its gRPC port in its Consul
 * registration as service meta {@code grpc-port}, which the gateway's {@code discovery:///} name
 * resolver reads. Off unless {@code shiguchi.discovery.client.expose-grpc-port=true}, and inert without
 * Spring Cloud Consul discovery on the classpath. Uses the same
 * {@code shiguchi.discovery.client.*} properties as the Eureka publisher; see
 * {@link ConsulGrpcPortPublisher}.
 */
@AutoConfiguration
@ConditionalOnClass({ConsulRegistrationCustomizer.class, ConsulClient.class})
@ConditionalOnProperty(prefix = "shiguchi.discovery.client", name = "expose-grpc-port", havingValue = "true")
@EnableConfigurationProperties(DiscoveryClientProperties.class)
public class ConsulGrpcPortAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConsulGrpcPortAutoConfiguration.class);

    /** Spring Cloud Consul applies every {@link ConsulRegistrationCustomizer} bean to its registration. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ConsulGrpcPortPublisher consulGrpcPortPublisher(
            DiscoveryClientProperties properties,
            ObjectProvider<GrpcServerPortSupplier> suppliers,
            ObjectProvider<ConsulClient> client,
            ObjectProvider<ConsulDiscoveryProperties> discoveryProperties,
            ObjectProvider<HeartbeatProperties> heartbeat,
            ObjectProvider<ConsulAutoServiceRegistration> autoRegistration,
            ObjectProvider<GrpcPortHold.Scheduler> scheduler) {
        return new ConsulGrpcPortPublisher(
                properties,
                suppliers,
                client,
                discoveryProperties,
                heartbeat,
                autoRegistration,
                scheduler.getIfAvailable());
    }

    /** Publishes a port that only became known after startup (a server bound to port 0). */
    @Bean
    public ConsulGrpcPortReadyListener consulGrpcPortReadyListener(
            ConsulGrpcPortPublisher publisher, DiscoveryClientProperties properties) {
        return new ConsulGrpcPortReadyListener(publisher, properties);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Server.class)
    static class GrpcServerDetection {

        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        @ConditionalOnMissingBean(GrpcServerBeanPortSupplier.class)
        GrpcServerBeanPortSupplier grpcServerBeanPortSupplier(ObjectProvider<Server> servers) {
            return new GrpcServerBeanPortSupplier(servers);
        }
    }

    /** See {@link GrpcPortRegistrationAutoConfiguration}: Spring gRPC keeps its server out of the bean factory. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent")
    static class SpringGrpcServerDetection {

        @Bean
        @Order(Ordered.LOWEST_PRECEDENCE)
        @ConditionalOnMissingBean(SpringGrpcServerPortSupplier.class)
        SpringGrpcServerPortSupplier springGrpcServerPortSupplier(
                org.springframework.core.env.Environment environment,
                ObjectProvider<GrpcPortSink> sinks,
                ObjectProvider<DiscoveryClientProperties> properties) {
            return new SpringGrpcServerPortSupplier(environment, sinks, properties);
        }
    }

    static class ConsulGrpcPortReadyListener {

        private final ConsulGrpcPortPublisher publisher;
        private final DiscoveryClientProperties properties;

        ConsulGrpcPortReadyListener(ConsulGrpcPortPublisher publisher, DiscoveryClientProperties properties) {
            this.publisher = publisher;
            this.properties = properties;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            OptionalInt port = publisher.resolvePort();
            if (port.isEmpty()) {
                log.warn("shiguchi.discovery.client.expose-grpc-port is on but no gRPC port was found: set"
                        + " shiguchi.discovery.client.grpc-port, declare a GrpcServerPortSupplier bean, or call"
                        + " ConsulGrpcPortPublisher.publish(port)");
                // Still held: the port may be published later; the timeout releases the service if not.
                publisher.startTimeout();
                return;
            }
            if (properties.isUpdateOnBind()) {
                publisher.publish(port.getAsInt());
            }
        }
    }
}
