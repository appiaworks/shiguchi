package dev.appiaworks.shiguchi.cursus;

import dev.appiaworks.shiguchi.config.GatewayMode;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

/**
 * Declares the hosted {@code PushService} to the gateway as one more service: STATIC mode (its
 * stubs are on the classpath), reached over {@code inprocess:<name>}, its REST paths under the
 * push path ({@code <path>/v1/publish}, {@code <path>/v1/subscribe}), no deadline. Appended to
 * {@code shiguchi.services} once the properties are bound, before the route loaders read
 * them, which is why this is a {@link BeanPostProcessor} on {@link GatewayProperties}. A service
 * the application already declares under the same name wins.
 */
public final class PushServiceRegistration implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(PushServiceRegistration.class);

    private final String serviceName;
    private final String pathPrefix;
    private final String inProcessName;

    public PushServiceRegistration(Environment environment) {
        this.serviceName = environment.getProperty("shiguchi.cursus.grpc.service-name", "push");
        this.pathPrefix = PushConfiguration.normalizePath(environment.getProperty("shiguchi.cursus.path", "/push"));
        // Unique per context: several gateways in one JVM (tests, mostly) must not share a server.
        this.inProcessName = "shiguchi-cursus-"
                + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    /** The in-process server's name; the service's target is {@code inprocess:<name>}. */
    public String inProcessName() {
        return inProcessName;
    }

    public String serviceName() {
        return serviceName;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof GatewayProperties properties) register(properties);
        return bean;
    }

    void register(GatewayProperties properties) {
        boolean declared =
                properties.getServices().stream().anyMatch(s -> s != null && serviceName.equals(s.getName()));
        if (declared) {
            log.info(
                    "Push: service '{}' is declared in shiguchi.services; not registering the hosted one", serviceName);
            return;
        }
        properties.getServices().add(definition());
    }

    ServiceDefinition definition() {
        ServiceDefinition def = new ServiceDefinition();
        def.setName(serviceName);
        def.setMode(GatewayMode.STATIC);
        def.setTarget("inprocess:" + inProcessName);
        def.getStaticServiceClasses().add(PushServiceGrpc.class.getName());
        def.setPathPrefix(pathPrefix);
        def.setDeadline(Duration.ZERO);
        def.setInsecure(true);
        return def;
    }
}
