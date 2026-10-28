package dev.appiaworks.shiguchi.serde;

import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.protobuf.extras.serde.ProtoJsonMapping;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Installs {@code shiguchi.json.*} as the process-wide {@link ProtoJsonMapping}, together with
 * every {@link ProtoScalarCodec} bean the application declares.
 *
 * <p>A bean, not a property-binding side effect, so the serde components can depend on it by
 * name: Tier 0 programs and Tier 2 serializers bake in which types are scalars when they are
 * built, and the mapping must be installed before the first of them is.
 */
@Component(ProtoJsonMappingConfigurer.BEAN_NAME)
public class ProtoJsonMappingConfigurer {

    public static final String BEAN_NAME = "protoJsonMappingConfigurer";

    private final ProtoJsonMapping mapping;

    public ProtoJsonMappingConfigurer(GatewayProperties properties, ObjectProvider<ProtoScalarCodec> scalarCodecs) {
        this.mapping = GatewayJsonSettings.mapping(properties.getJson());
        List<ProtoScalarCodec> codecs = scalarCodecs.orderedStream().toList();
        ProtoJsonMapping.install(mapping, codecs, properties.getJson().isStrictInput());
        JsonPrintOptions.install(
                GatewayJsonSettings.printOptions(properties.getJson().getPrint()));
    }

    /** The mapping this gateway installed. */
    public ProtoJsonMapping mapping() {
        return mapping;
    }
}
