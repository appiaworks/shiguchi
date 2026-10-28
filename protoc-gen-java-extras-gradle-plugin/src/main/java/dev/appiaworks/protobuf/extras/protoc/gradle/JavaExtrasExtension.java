package dev.appiaworks.protobuf.extras.protoc.gradle;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code javaExtras { }} block.
 *
 * <pre>{@code
 * javaExtras {
 *     // No accessors for fields of this type.
 *     disable("google.type.Decimal")
 *
 *     // Accessors for a message type of your own: its Java type and two static converters.
 *     map("acme.Sku", "com.acme.SkuCode", "com.acme.Skus.toCode", "com.acme.Skus.toProto")
 *
 *     // No writeJson methods on the messages.
 *     json.set(false)
 * }
 * }</pre>
 */
public abstract class JavaExtrasExtension {

    /** Proto types, by full name, whose fields get no accessors. */
    public abstract ListProperty<String> getDisabled();

    /** Custom mappings, each in the protoc plugin's {@code map=} form. */
    public abstract ListProperty<String> getMappings();

    /**
     * Whether to add {@code io.github.appiaworks:protobuf-java-extras}, which the generated accessors
     * call, to the project's dependencies. On by default; switch it off to manage the dependency
     * yourself.
     */
    public abstract Property<Boolean> getAddRuntimeDependency();

    /**
     * Whether every message gets a {@code writeJson(JsonGenerator)} method and its Builder a
     * {@code mergeJson(JsonParser)}: the typed JSON paths {@code ProtoJson} (and, for writing,
     * the gateway) use on any Java. On by default.
     */
    public abstract Property<Boolean> getJson();

    public JavaExtrasExtension() {
        getAddRuntimeDependency().convention(true);
        getJson().convention(true);
    }

    /** No accessors for fields of this proto type, e.g. {@code google.type.Decimal}. */
    public void disable(String protoType) {
        getDisabled().add(protoType);
    }

    /**
     * Accessors for a message type of your own. Mapping a type that has built-in accessors
     * replaces them.
     *
     * @param protoType the message's full proto name, e.g. {@code acme.Sku}
     * @param javaType the Java type fields of it are read and set as, e.g. {@code com.acme.SkuCode}
     * @param toJava a static method taking the generated message and returning {@code javaType},
     *     named as {@code package.Class.method}
     * @param fromJava a static method taking {@code javaType} and returning the generated message
     */
    public void map(String protoType, String javaType, String toJava, String fromJava) {
        getMappings().add(String.join(";", protoType, javaType, toJava, fromJava));
    }

    /** The options handed to the protoc plugin. */
    List<String> toOptions() {
        List<String> options = new ArrayList<>();
        for (String type : getDisabled().get()) options.add("disable=" + type);
        for (String mapping : getMappings().get()) options.add("map=" + mapping);
        if (!getJson().get()) options.add("json=off");
        return options;
    }
}
