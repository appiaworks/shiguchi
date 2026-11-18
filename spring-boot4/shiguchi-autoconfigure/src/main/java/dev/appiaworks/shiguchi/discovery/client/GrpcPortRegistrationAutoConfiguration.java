package dev.appiaworks.shiguchi.discovery.client;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import io.grpc.Server;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.netflix.eureka.EurekaInstanceConfigBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Runs in a <em>backend</em> app (not the gateway) and publishes its gRPC port in its Eureka
 * registration as {@code grpc-port}, which the gateway's {@code discovery:///} name resolver reads.
 * Off unless {@code shiguchi.discovery.client.expose-grpc-port=true}, and inert without the Eureka
 * client on the classpath. It does not use {@code GatewayProperties} or the gateway's component scan.
 */
@AutoConfiguration(afterName = "org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration")
@ConditionalOnClass({EurekaInstanceConfigBean.class, ApplicationInfoManager.class})
@ConditionalOnProperty(prefix = "shiguchi.discovery.client", name = "expose-grpc-port", havingValue = "true")
@EnableConfigurationProperties(DiscoveryClientProperties.class)
public class GrpcPortRegistrationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GrpcPortRegistrationAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public GrpcPortPublisher grpcPortPublisher(
            DiscoveryClientProperties properties,
            ObjectProvider<ApplicationInfoManager> infoManager,
            ObjectProvider<com.netflix.discovery.EurekaClient> eurekaClient,
            ObjectProvider<GrpcServerPortSupplier> suppliers,
            ObjectProvider<GrpcPortHold> holds) {
        return new GrpcPortPublisher(properties, infoManager, eurekaClient, suppliers, holds);
    }

    /** Keeps the instance STARTING until a late port is published; see {@link GrpcPortHold}. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public GrpcPortHold grpcPortHold(
            DiscoveryClientProperties properties,
            ObjectProvider<ApplicationInfoManager> infoManager,
            ObjectProvider<GrpcPortHold.Scheduler> scheduler) {
        return new GrpcPortHold(properties, infoManager, scheduler.getIfAvailable());
    }

    /** Adds the port to the instance config before the instance registers, when it is already known. */
    @Bean
    public static GrpcPortMetadataPostProcessor grpcPortMetadataPostProcessor(
            ObjectProvider<GrpcPortPublisher> publisher,
            ObjectProvider<GrpcPortHold> hold,
            ObjectProvider<DiscoveryClientProperties> properties) {
        return new GrpcPortMetadataPostProcessor(publisher, hold, properties);
    }

    /** Publishes a port that only became known after startup (a server bound to port 0). */
    @Bean
    public GrpcPortReadyListener grpcPortReadyListener(
            GrpcPortPublisher publisher, DiscoveryClientProperties properties, GrpcPortHold hold) {
        return new GrpcPortReadyListener(publisher, properties, hold);
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

    /**
     * Spring gRPC (Spring Boot 4.1's {@code spring-boot-grpc-server}) keeps its server out of the bean
     * factory. Nested so the auto-configuration loads without it.
     */
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

    static class GrpcPortMetadataPostProcessor implements BeanPostProcessor {

        private final ObjectProvider<GrpcPortPublisher> publisher;
        private final ObjectProvider<GrpcPortHold> hold;
        private final ObjectProvider<DiscoveryClientProperties> properties;

        GrpcPortMetadataPostProcessor(
                ObjectProvider<GrpcPortPublisher> publisher,
                ObjectProvider<GrpcPortHold> hold,
                ObjectProvider<DiscoveryClientProperties> properties) {
            this.publisher = publisher;
            this.hold = hold;
            this.properties = properties;
        }

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof EurekaInstanceConfigBean config) {
                GrpcPortPublisher p = publisher.getObject();
                OptionalInt port = p.resolveStaticPort();
                if (port.isPresent()) {
                    p.applyTo(config.getMetadataMap(), port.getAsInt());
                } else if (holdWanted()) {
                    // The port is only known after the server binds: register STARTING (not UP) without
                    // grpc-port. GrpcPortPublisher.publish moves it to UP together with the metadata.
                    if (config.getInitialStatus() == InstanceInfo.InstanceStatus.UP) {
                        config.setInitialStatus(InstanceInfo.InstanceStatus.STARTING);
                        // Also start the InstanceInfo itself as STARTING (it is built UP otherwise).
                        config.setInstanceEnabledOnit(false);
                        hold.getObject().engage();
                        log.info("The gRPC port is not known yet: registering the Eureka instance as STARTING"
                                + " until it is published (shiguchi.discovery.client.hold-until-published)");
                    }
                }
            }
            return bean;
        }

        /** Guards the app's health check handler so a healthy app does not leave STARTING early. */
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof HealthCheckHandler handler && holdWanted() && !isGuarded(bean)) {
                return hold.getObject().guard(handler);
            }
            return bean;
        }

        private static boolean isGuarded(Object bean) {
            return AopUtils.isAopProxy(bean);
        }

        private boolean holdWanted() {
            DiscoveryClientProperties p = properties.getIfAvailable();
            return p != null && p.isHoldUntilPublished() && p.isUpdateOnBind();
        }
    }

    static class GrpcPortReadyListener {

        private final GrpcPortPublisher publisher;
        private final DiscoveryClientProperties properties;
        private final GrpcPortHold hold;

        GrpcPortReadyListener(GrpcPortPublisher publisher, DiscoveryClientProperties properties, GrpcPortHold hold) {
            this.publisher = publisher;
            this.properties = properties;
            this.hold = hold;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void onReady() {
            OptionalInt port = publisher.resolvePort();
            if (port.isEmpty()) {
                log.warn("shiguchi.discovery.client.expose-grpc-port is on but no gRPC port was found: set"
                        + " shiguchi.discovery.client.grpc-port, declare a GrpcServerPortSupplier bean, or call"
                        + " GrpcPortPublisher.publish(port)");
                // Still held: the port may be published later; the timeout releases the instance if not.
                hold.startTimeout();
                return;
            }
            if (properties.isUpdateOnBind()) {
                publisher.publish(port.getAsInt());
            }
        }
    }
}
