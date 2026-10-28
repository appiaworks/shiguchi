package dev.appiaworks.shiguchi.cursus;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.JsonNumberFormat;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.shiguchi.serde.ProtoJsonText;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the built-in sources share: a record's payload as the event's JSON, typed when a message type is set. */
public final class SourcePayloads {

    private SourcePayloads() {}

    /** The three payload parts of a {@link SourcedEvent}. */
    public record Payload(byte[] json, String typeUrl, byte[] proto) {}

    /** The variables a message offers a topic template: its singular scalar fields, as text. */
    public static Map<String, String> variables(Message message) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            if (field.isRepeated()
                    || field.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                    || field.getJavaType() == FieldDescriptor.JavaType.BYTE_STRING) continue;
            Object value = message.getField(field);
            if (value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor ev)
                vars.put(field.getName(), ev.getName());
            else vars.put(field.getName(), String.valueOf(value));
        }
        return vars;
    }

    /** A parsed message as a typed payload: JSON rendering, type URL and bytes. */
    public static Payload typed(Message message, JsonFormat.TypeRegistry registry) throws Exception {
        return typed(message, registry, JsonPrintOptions.global().numberFormat());
    }

    /** {@link #typed(Message, JsonFormat.TypeRegistry)} with the number layout of the service the message came from. */
    public static Payload typed(Message message, JsonFormat.TypeRegistry registry, JsonNumberFormat numbers)
            throws Exception {
        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        if (registry != null) printer = printer.usingTypeRegistry(registry);
        byte[] json = ProtoJsonText.printUtf8(printer, message, numbers);
        return new Payload(
                json, "type.googleapis.com/" + message.getDescriptorForType().getFullName(), message.toByteArray());
    }

    /** Raw record bytes as a payload: parsed as {@code type} in protobuf or JSON when a type is set, kept as they are otherwise. */
    public static Payload of(
            byte[] value, Descriptor type, PushProperties.PayloadFormat format, JsonFormat.TypeRegistry registry)
            throws Exception {
        if (type == null) return new Payload(value, null, null);
        DynamicMessage message;
        if (format == PushProperties.PayloadFormat.JSON) {
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(type);
            JsonFormat.Parser parser = JsonFormat.parser().ignoringUnknownFields();
            if (registry != null) parser = parser.usingTypeRegistry(registry);
            parser.merge(new String(value, StandardCharsets.UTF_8), builder);
            message = builder.build();
        } else {
            message = DynamicMessage.parseFrom(type, value);
        }
        return typed(message, registry);
    }
}
