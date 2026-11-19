package dev.appiaworks.shiguchi.routing;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import dev.appiaworks.shiguchi.config.GrpcLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.function.Function;

/**
 * Applies a service's {@code loaders[]} YAML to the route of the rpc it names. The YAML entry
 * replaces whatever the proto options said on that method, as the YAML wins over the proto
 * everywhere.
 */
final class YamlLoaders {

    private YamlLoaders() {}

    static void apply(
            RouteDefinition.Builder builder,
            ServiceDefinition svcDef,
            ServiceDescriptor sd,
            MethodDescriptor md,
            Function<Descriptor, String> inferIdsField) {
        if (svcDef == null || svcDef.getLoaders() == null) return;
        String rpc = sd.getFullName() + "/" + md.getName();
        for (GrpcLoaderConfig loader : svcDef.getLoaders()) {
            if (loader.getType() == null || loader.getType().isBlank() || !rpc.equals(loader.getRpc())) continue;
            String idsField =
                    loader.getIdsField() == null || loader.getIdsField().isBlank()
                            ? inferIdsField.apply(md.getInputType())
                            : loader.getIdsField();
            builder.loaderFor(
                    loader.getType(), idsField, loader.getResultField() == null ? "" : loader.getResultField());
            builder.loaderGroupBy(loader.getGroupBy() == null ? "" : loader.getGroupBy());
            return;
        }
    }
}
