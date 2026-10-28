package dev.appiaworks.protobuf.extras.serde;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import dev.appiaworks.protobuf.extras.ProtoScalarCodec;
import dev.appiaworks.protobuf.extras.ProtoTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code google.type} messages {@code shiguchi.json.scalar-types} switches on, as
 * {@link ProtoScalarCodec}s over {@link GoogleTypeJson}: the same registry serves them and the
 * codecs an application adds.
 */
final class BuiltInScalarCodecs {

    private BuiltInScalarCodecs() {}

    private static final List<BuiltIn> ALL = List.of(
            new BuiltIn(ProtoJsonMapping.DATE, ProtoScalarCodec.JsonShape.STRING),
            new BuiltIn(ProtoJsonMapping.TIME_OF_DAY, ProtoScalarCodec.JsonShape.STRING),
            new BuiltIn(ProtoJsonMapping.DATE_TIME, ProtoScalarCodec.JsonShape.STRING),
            new BuiltIn(ProtoJsonMapping.DECIMAL, ProtoScalarCodec.JsonShape.STRING),
            new BuiltIn(ProtoJsonMapping.MONEY, ProtoScalarCodec.JsonShape.ANY));

    /** The built-in codecs the mapping switches on, by full name. */
    static Map<String, ProtoScalarCodec> enabledBy(ProtoJsonMapping mapping) {
        Map<String, ProtoScalarCodec> enabled = new HashMap<>();
        for (BuiltIn codec : ALL) {
            if (mapping.maps(codec.fullName())) enabled.put(codec.fullName(), codec);
        }
        return enabled;
    }

    /**
     * One mapped {@code google.type} message. They read the object form themselves — Money's
     * flattened one included — so {@link ProtoWkt} hands them every value as it arrives.
     */
    record BuiltIn(String fullName, ProtoScalarCodec.JsonShape jsonShape) implements ProtoScalarCodec {

        @Override
        public Object toJson(Message message) {
            // A generated class re-read from its bytes: a DateTime's TimeZone must arrive dynamic too.
            return GoogleTypeJson.toJson(ProtoTypes.dynamic(message, message.getDescriptorForType()));
        }

        @Override
        public Message fromJson(Object json, Descriptor descriptor) {
            return GoogleTypeJson.fromJava(json, descriptor);
        }
    }
}
