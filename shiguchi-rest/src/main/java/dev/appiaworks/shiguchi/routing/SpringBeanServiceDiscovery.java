package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.grpc.GatewayHostedService;
import io.grpc.BindableService;
import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * P4-B: Spring bean auto-discovery for gRPC static service classes.
 *
 * When a STATIC service has autoDiscover.springBeans = true, this queries the
 * Spring ApplicationContext for all BindableService beans and adds the generated
 * *Grpc class of each to staticServiceClasses, so StaticRouteLoader can load its
 * proto descriptors.
 *
 * The *Grpc class comes from the schema supplier the bean's service descriptor carries, which
 * generated code nests in that class. The bean's own class says nothing reliable: it is normally a
 * top-level subclass of *ImplBase, it can be a proxy, and its name can end in Grpc by coincidence.
 *
 * Skipped: services the gateway hosts itself, and gRPC's own services (health, reflection), which
 * an application registers as beans but which are not part of its API.
 *
 * Activation: shiguchi.services[*].auto-discover.spring-beans: true
 */
@Component
public class SpringBeanServiceDiscovery {

    private static final Logger log = LoggerFactory.getLogger(SpringBeanServiceDiscovery.class);

    /** The proto package gRPC reserves for its own services, such as grpc.health.v1.Health. */
    private static final String GRPC_INFRASTRUCTURE_PREFIX = "grpc.";

    private final ApplicationContext applicationContext;

    public SpringBeanServiceDiscovery(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void populateIfEnabled(ServiceDefinition svcDef) {
        ServiceDefinition.AutoDiscoverConfig cfg = svcDef.getAutoDiscover();
        if (!cfg.isSpringBeans()) return;

        log.info("Spring-bean auto-discovery for service '{}'", svcDef.getName());

        Map<String, BindableService> beans = applicationContext.getBeansOfType(BindableService.class);
        // A set: two beans can implement the same generated service.
        Set<String> resolved = new LinkedHashSet<>();
        beans.forEach((beanName, bean) -> {
            String grpcClassName = resolveGrpcClassName(beanName, bean, svcDef);
            if (grpcClassName != null) resolved.add(grpcClassName);
        });

        // Discovery runs again on every load retry, when an earlier pass already listed these.
        List<String> listed = svcDef.getStaticServiceClasses();
        List<String> discovered =
                resolved.stream().filter(name -> !listed.contains(name)).toList();

        if (!discovered.isEmpty()) {
            listed.addAll(discovered);
            log.info(
                    "Spring-bean discovered {} gRPC stub class(es) for service '{}': {}",
                    discovered.size(),
                    svcDef.getName(),
                    discovered);
        } else if (resolved.isEmpty()) {
            log.warn(
                    "Spring-bean discovery found no routable BindableService bean for service '{}' ({} bean(s) examined)",
                    svcDef.getName(),
                    beans.size());
        }
    }

    /** The generated *Grpc class behind this bean, or null when the bean adds no routes. */
    private static String resolveGrpcClassName(String beanName, BindableService bean, ServiceDefinition svcDef) {
        if (bean instanceof GatewayHostedService) {
            log.debug("Spring-bean discovery skips gateway-hosted service bean '{}'", beanName);
            return null;
        }

        ServiceDescriptor descriptor = bean.bindService().getServiceDescriptor();
        if (descriptor == null) {
            warnUnresolved(beanName, bean, svcDef, "it has no service descriptor");
            return null;
        }
        if (descriptor.getName().startsWith(GRPC_INFRASTRUCTURE_PREFIX)) {
            log.debug("Spring-bean discovery skips gRPC infrastructure service '{}'", descriptor.getName());
            return null;
        }

        Object schema = descriptor.getSchemaDescriptor();
        if (!(schema instanceof ProtoServiceDescriptorSupplier)) {
            warnUnresolved(beanName, bean, svcDef, "its service descriptor carries no protobuf schema");
            return null;
        }

        Class<?> grpcClass = schema.getClass().getEnclosingClass();
        if (grpcClass == null || !isGeneratedGrpcClassOf(grpcClass, descriptor.getName())) {
            warnUnresolved(
                    beanName,
                    bean,
                    svcDef,
                    "no generated *Grpc class describes its service '" + descriptor.getName() + "'");
            return null;
        }
        return grpcClass.getName();
    }

    /** What StaticRouteLoader will call on this class has to answer for the bean's service. */
    private static boolean isGeneratedGrpcClassOf(Class<?> candidate, String serviceName) {
        if (!candidate.getSimpleName().endsWith("Grpc")) return false;
        try {
            Method method = candidate.getMethod("getServiceDescriptor");
            return Modifier.isStatic(method.getModifiers())
                    && method.invoke(null) instanceof ServiceDescriptor declared
                    && serviceName.equals(declared.getName());
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private static void warnUnresolved(String beanName, BindableService bean, ServiceDefinition svcDef, String reason) {
        log.warn(
                "Spring-bean discovery for service '{}' adds no routes for bean '{}' ({}): {}. List its *Grpc class in static-service-classes if it should have routes.",
                svcDef.getName(),
                beanName,
                bean.getClass().getName(),
                reason);
    }
}
