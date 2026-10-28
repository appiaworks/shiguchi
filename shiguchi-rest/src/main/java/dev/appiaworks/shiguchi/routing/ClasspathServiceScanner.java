package dev.appiaworks.shiguchi.routing;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P4-A: Classpath scanner for gRPC static service discovery.
 *
 * When a STATIC service has autoDiscover.classpath = true, this scans the specified
 * packages (or full classpath) for *Grpc outer classes that expose a static
 * getServiceDescriptor() returning an io.grpc.ServiceDescriptor with a
 * ProtoServiceDescriptorSupplier — and adds their names to staticServiceClasses.
 *
 * Activation: shiguchi.services[*].auto-discover.classpath: true
 */
@Component
public class ClasspathServiceScanner {

    private static final Logger log = LoggerFactory.getLogger(ClasspathServiceScanner.class);

    public void populateIfEnabled(ServiceDefinition svcDef) {
        ServiceDefinition.AutoDiscoverConfig cfg = svcDef.getAutoDiscover();
        if (!cfg.isClasspath()) return;

        log.info(
                "Auto-discovering gRPC stub classes for service '{}' in packages: {}",
                svcDef.getName(),
                cfg.getScanPackages().isEmpty() ? "[all]" : cfg.getScanPackages());

        List<String> discovered = new ArrayList<>();
        List<String> packages = cfg.getScanPackages();

        ClassGraph scanner =
                new ClassGraph().enableClassInfo().enableMethodInfo().disableRuntimeInvisibleAnnotations();

        if (!packages.isEmpty()) {
            scanner.acceptPackages(packages.toArray(new String[0]));
        }

        try (ScanResult result = scanner.scan()) {
            for (ClassInfo ci : result.getAllClasses()) {
                String name = ci.getName();
                // gRPC outer class pattern: ends with "Grpc", has static getServiceDescriptor()
                if (!name.endsWith("Grpc")) continue;
                if (!ci.hasMethod("getServiceDescriptor")) continue;

                try {
                    Class<?> clazz = ci.loadClass();
                    io.grpc.ServiceDescriptor grpcSd = (io.grpc.ServiceDescriptor)
                            clazz.getMethod("getServiceDescriptor").invoke(null);
                    if (grpcSd == null) continue;

                    Object schema = grpcSd.getSchemaDescriptor();
                    if (!(schema instanceof ProtoServiceDescriptorSupplier)) continue;

                    // Avoid duplicates with explicitly listed classes
                    if (!svcDef.getStaticServiceClasses().contains(name)) {
                        discovered.add(name);
                        log.debug("Auto-discovered gRPC stub class '{}' for service '{}'", name, svcDef.getName());
                    }
                } catch (Exception e) {
                    log.debug("Skipping candidate '{}': {}", name, e.getMessage());
                }
            }
        }

        if (!discovered.isEmpty()) {
            svcDef.getStaticServiceClasses().addAll(discovered);
            log.info(
                    "Auto-discovered {} gRPC stub class(es) for service '{}': {}",
                    discovered.size(),
                    svcDef.getName(),
                    discovered);
        } else {
            log.warn(
                    "Auto-discovery found no gRPC stub classes for service '{}' in packages: {}",
                    svcDef.getName(),
                    packages.isEmpty() ? "[all]" : packages);
        }
    }
}
