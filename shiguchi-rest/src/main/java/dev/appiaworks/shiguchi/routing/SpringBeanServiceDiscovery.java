package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * P4-B: Spring bean auto-discovery for gRPC static service classes.
 *
 * When a STATIC service has autoDiscover.springBeans = true, this queries the
 * Spring ApplicationContext for all BindableService beans and extracts the
 * corresponding *Grpc outer class name from the service descriptor — adding it
 * to staticServiceClasses so StaticRouteLoader can load its proto descriptors.
 *
 * Activation: shiguchi.services[*].auto-discover.spring-beans: true
 */
@Component
public class SpringBeanServiceDiscovery {

    private static final Logger log = LoggerFactory.getLogger(SpringBeanServiceDiscovery.class);

    private final ApplicationContext applicationContext;

    public SpringBeanServiceDiscovery(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void populateIfEnabled(ServiceDefinition svcDef) {
        ServiceDefinition.AutoDiscoverConfig cfg = svcDef.getAutoDiscover();
        if (!cfg.isSpringBeans()) return;

        log.info("Spring-bean auto-discovery for service '{}'", svcDef.getName());

        Map<String, BindableService> beans = applicationContext.getBeansOfType(BindableService.class);
        List<String> discovered = new ArrayList<>();

        for (BindableService bean : beans.values()) {
            ServerServiceDefinition def = bean.bindService();
            io.grpc.ServiceDescriptor grpcSd = def.getServiceDescriptor();
            if (grpcSd == null) continue;

            Object schema = grpcSd.getSchemaDescriptor();
            if (!(schema instanceof ProtoServiceDescriptorSupplier)) continue;

            // Derive the *Grpc outer class name from the service descriptor's supplier
            String grpcClassName = deriveGrpcClassName(bean);
            if (grpcClassName == null) continue;

            if (!svcDef.getStaticServiceClasses().contains(grpcClassName)) {
                discovered.add(grpcClassName);
                log.debug(
                        "Spring-bean discovered gRPC stub class '{}' for service '{}'",
                        grpcClassName,
                        svcDef.getName());
            }
        }

        if (!discovered.isEmpty()) {
            svcDef.getStaticServiceClasses().addAll(discovered);
            log.info(
                    "Spring-bean discovered {} gRPC stub class(es) for service '{}': {}",
                    discovered.size(),
                    svcDef.getName(),
                    discovered);
        } else {
            log.warn("Spring-bean discovery found no BindableService beans for service '{}'", svcDef.getName());
        }
    }

    private static String deriveGrpcClassName(BindableService bean) {
        // Generated *ImplBase classes are nested inside the *Grpc outer class.
        // e.g. com.example.FooServiceGrpc.FooServiceImplBase → com.example.FooServiceGrpc
        Class<?> beanClass = bean.getClass();
        Class<?> enclosing = beanClass.getEnclosingClass();
        if (enclosing != null && enclosing.getSimpleName().endsWith("Grpc")) {
            return enclosing.getName();
        }
        // Fallback: bean itself may be a stub (unusual)
        String name = beanClass.getName();
        if (name.endsWith("Grpc")) return name;
        return null;
    }
}
