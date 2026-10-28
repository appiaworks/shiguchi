package dev.appiaworks.shiguchi.routing;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.StructProto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Projects a downstream OpenAPI document onto a synthetic proto {@link FileDescriptor}: one
 * message per component schema (and per inline object), one service with a unary method per
 * operation, whose request message carries the operation's parameters and body fields and whose
 * response message is the declared response schema.
 *
 * <p>This is what lets a REST backend take part in the GraphQL surface without a second schema
 * model: the SDL generator, the field-mask and paging conventions, the Map converters and the
 * Tier 2 serializers all consume descriptors and never learn where these came from.
 *
 * <p>JSON Schema to proto mapping:
 * <table>
 *   <tr><th>JSON Schema</th><th>proto</th></tr>
 *   <tr><td>{@code $ref} to a component</td><td>message of that name (type prefix applied)</td></tr>
 *   <tr><td>object with properties</td><td>message ({@code <Parent>_<field>} when inline)</td></tr>
 *   <tr><td>object with only additionalProperties</td><td>{@code map<string, T>}</td></tr>
 *   <tr><td>array</td><td>{@code repeated T}; arrays of arrays fall back to JSON</td></tr>
 *   <tr><td>string enum</td><td>enum with an {@code _UNSPECIFIED} zero value</td></tr>
 *   <tr><td>integer</td><td>int32 for format int32, else int64</td></tr>
 *   <tr><td>number</td><td>float for format float, else double</td></tr>
 *   <tr><td>boolean / string</td><td>bool / string (formats such as date-time stay strings)</td></tr>
 *   <tr><td>allOf</td><td>properties merged</td></tr>
 *   <tr><td>oneOf, anyOf, untyped, empty object</td><td>{@code google.protobuf.Value} (GraphQL {@code JSON})</td></tr>
 * </table>
 * Nullability is not carried: proto3 fields are optional in GraphQL anyway.
 */
public final class OpenApiToDescriptor {

    private static final Logger log = LoggerFactory.getLogger(OpenApiToDescriptor.class);

    private static final String VALUE_TYPE = ".google.protobuf.Value";
    private static final String STRUCT_TYPE = ".google.protobuf.Struct";
    private static final String EMPTY_TYPE = ".google.protobuf.Empty";

    /** One synthetic method: how the loader names it and how the dispatcher calls it. */
    public record Method(String name, String operationId, HttpOperationBinding binding) {}

    /**
     * @param file the built descriptor; its single service is {@link #serviceFqn}
     * @param serviceFqn fully-qualified synthetic service name
     * @param methodsByOperationId one entry per operation, keyed by the document's operationId
     */
    public record Result(
            FileDescriptor file,
            String serviceFqn,
            Map<String, Method> methodsByOperationId,
            Map<String, Map<String, String>> enumValueOriginals) {

        public Result(FileDescriptor file, String serviceFqn, Map<String, Method> methodsByOperationId) {
            this(file, serviceFqn, methodsByOperationId, Map.of());
        }
    }

    private OpenApiToDescriptor() {}

    /** Package for a service: {@code rest.<sanitised service name>}. */
    public static String packageFor(String serviceName) {
        return "rest." + sanitizeIdentifier(serviceName).toLowerCase(Locale.ROOT);
    }

    /** Synthetic service FQN for a service, whether or not descriptors are built for it. */
    public static String serviceFqnFor(String serviceName) {
        return packageFor(serviceName) + "." + pascal(serviceName) + "Service";
    }

    public static Result build(String serviceName, String typePrefix, DownstreamOpenApiDocument doc)
            throws DescriptorValidationException {
        return new Builder(serviceName, typePrefix != null ? typePrefix : "", doc).build();
    }

    // -------------------------------------------------------------------------

    private static final class Builder {
        private final String serviceName;
        private final String pkg;
        private final String typePrefix;
        private final DownstreamOpenApiDocument doc;

        private final Map<String, DescriptorProto.Builder> messages = new LinkedHashMap<>();
        private final Map<String, EnumDescriptorProto.Builder> enums = new LinkedHashMap<>();

        /** Enum full name → (proto value name → the document's own value string). */
        private final Map<String, Map<String, String>> enumOriginals = new LinkedHashMap<>();

        private final Set<String> building = new HashSet<>();
        private final Map<String, Method> methods = new LinkedHashMap<>();
        private final ServiceDescriptorProto.Builder service = ServiceDescriptorProto.newBuilder();

        Builder(String serviceName, String typePrefix, DownstreamOpenApiDocument doc) {
            this.serviceName = serviceName;
            this.pkg = packageFor(serviceName);
            this.typePrefix = typePrefix;
            this.doc = doc;
        }

        Result build() throws DescriptorValidationException {
            service.setName(pascal(serviceName) + "Service");

            // Components first, so operations referencing them find the names already claimed.
            for (String name : doc.componentSchemas().keySet()) {
                componentMessage(name);
            }

            Set<String> methodNames = new HashSet<>();
            for (DownstreamOpenApiDocument.Operation op : doc.operations()) {
                String methodName = uniqueMethodName(op, methodNames);
                String pascalOp = pascal(methodName);

                DescriptorProto.Builder request = claimMessage(pascalOp + "Request");
                List<String> pathParams = new ArrayList<>();
                List<String> queryParams = new ArrayList<>();
                Set<String> requestFields = new HashSet<>();
                for (DownstreamOpenApiDocument.Parameter p : op.parameters()) {
                    String fieldName = fieldName(p.name());
                    if (!requestFields.add(fieldName)) continue;
                    addField(request, fieldName, spec(p.schema(), pascalOp + "Request_" + fieldName));
                    (p.in().equals("path") ? pathParams : queryParams).add(fieldName);
                }
                boolean hasBody = false;
                if (op.requestBodySchema() != null) {
                    hasBody = true;
                    addBodyFields(request, op.requestBodySchema(), pascalOp + "Request", requestFields);
                }

                String responseType;
                boolean responseIsArray = false;
                boolean responseIsScalar = false;
                Map<String, Object> rs = op.responseSchema();
                if (rs == null) {
                    responseType = EMPTY_TYPE;
                } else {
                    FieldSpec spec = spec(rs, pascalOp + "Response");
                    if (spec.repeated || spec.mapValue != null) {
                        DescriptorProto.Builder response = claimMessage(pascalOp + "Response");
                        addField(response, HttpOperationBinding.ARRAY_FIELD, spec);
                        responseType = "." + pkg + "." + response.getName();
                        responseIsArray = spec.repeated;
                    } else if (spec.type == FieldDescriptorProto.Type.TYPE_MESSAGE
                            && !VALUE_TYPE.equals(spec.typeName)
                            && !STRUCT_TYPE.equals(spec.typeName)) {
                        responseType = spec.typeName;
                    } else {
                        DescriptorProto.Builder response = claimMessage(pascalOp + "Response");
                        addField(response, HttpOperationBinding.SCALAR_FIELD, spec);
                        responseType = "." + pkg + "." + response.getName();
                        responseIsScalar = true;
                    }
                }

                service.addMethod(MethodDescriptorProto.newBuilder()
                        .setName(methodName)
                        .setInputType("." + pkg + "." + request.getName())
                        .setOutputType(responseType));
                methods.put(
                        op.operationId(),
                        new Method(
                                methodName,
                                op.operationId(),
                                new HttpOperationBinding(
                                        op.httpMethod(),
                                        op.path(),
                                        pathParams,
                                        queryParams,
                                        hasBody,
                                        responseIsArray,
                                        responseIsScalar,
                                        op.requestMediaTypes(),
                                        op.responseMediaTypes(),
                                        op.requestSchemaName(),
                                        op.responseSchemaName())));
            }

            FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder()
                    .setName(pkg.replace('.', '/') + "/openapi.proto")
                    .setPackage(pkg)
                    .setSyntax("proto3")
                    .addDependency("google/protobuf/struct.proto")
                    .addDependency("google/protobuf/empty.proto")
                    .addService(service);
            enums.values().forEach(file::addEnumType);
            messages.values().forEach(file::addMessageType);

            FileDescriptor fd = FileDescriptor.buildFrom(
                    file.build(), new FileDescriptor[] {StructProto.getDescriptor(), EmptyProto.getDescriptor()});
            log.debug(
                    "Built synthetic descriptor for REST_DOWNSTREAM service '{}': {} messages, {} enums, {} methods",
                    serviceName,
                    messages.size(),
                    enums.size(),
                    methods.size());
            return new Result(fd, pkg + "." + service.getName(), Map.copyOf(methods), Map.copyOf(enumOriginals));
        }

        // ---- schemas ----------------------------------------------------------

        /** A resolved field type. */
        private record FieldSpec(
                FieldDescriptorProto.Type type, String typeName, boolean repeated, FieldSpec mapValue) {}

        private static FieldSpec scalar(FieldDescriptorProto.Type t) {
            return new FieldSpec(t, null, false, null);
        }

        private static FieldSpec message(String fqTypeName) {
            return new FieldSpec(FieldDescriptorProto.Type.TYPE_MESSAGE, fqTypeName, false, null);
        }

        private FieldSpec spec(Map<String, Object> schema, String hint) {
            if (schema == null || schema.isEmpty()) return message(VALUE_TYPE);

            Object ref = schema.get("$ref");
            if (ref instanceof String r) {
                String name = componentNameOf(r);
                if (name != null && doc.componentSchemas().containsKey(name)) {
                    return componentEnumOrMessage(name);
                }
                log.warn("Unresolvable $ref '{}' in REST_DOWNSTREAM service '{}'; using JSON", r, serviceName);
                return message(VALUE_TYPE);
            }

            if (schema.get("allOf") instanceof List<?> parts) {
                Map<String, Object> merged = mergeAllOf(parts);
                if (!merged.isEmpty()) return spec(merged, hint);
            }
            if (schema.containsKey("oneOf") || schema.containsKey("anyOf")) return message(VALUE_TYPE);

            String type = typeOf(schema);
            if (schema.get("enum") instanceof List<?> values && (type == null || type.equals("string"))) {
                return new FieldSpec(
                        FieldDescriptorProto.Type.TYPE_ENUM, "." + pkg + "." + enumType(hint, values), false, null);
            }
            if (type == null) {
                type = schema.containsKey("properties") ? "object" : schema.containsKey("items") ? "array" : null;
            }
            if (type == null) return message(VALUE_TYPE);

            return switch (type) {
                case "string" ->
                    "byte".equals(schema.get("format"))
                            ? scalar(FieldDescriptorProto.Type.TYPE_BYTES)
                            : scalar(FieldDescriptorProto.Type.TYPE_STRING);
                case "integer" ->
                    "int32".equals(schema.get("format"))
                            ? scalar(FieldDescriptorProto.Type.TYPE_INT32)
                            : scalar(FieldDescriptorProto.Type.TYPE_INT64);
                case "number" ->
                    "float".equals(schema.get("format"))
                            ? scalar(FieldDescriptorProto.Type.TYPE_FLOAT)
                            : scalar(FieldDescriptorProto.Type.TYPE_DOUBLE);
                case "boolean" -> scalar(FieldDescriptorProto.Type.TYPE_BOOL);
                case "array" -> {
                    FieldSpec item = spec(asMap(schema.get("items")), hint + "Item");
                    if (item.repeated || item.mapValue != null) yield message(VALUE_TYPE); // array of arrays / maps
                    yield new FieldSpec(item.type, item.typeName, true, null);
                }
                case "object" -> objectSpec(schema, hint);
                default -> message(VALUE_TYPE);
            };
        }

        private FieldSpec objectSpec(Map<String, Object> schema, String hint) {
            Map<String, Object> props = asMap(schema.get("properties"));
            if (!props.isEmpty()) {
                DescriptorProto.Builder msg = claimMessage(hint);
                Set<String> seen = new HashSet<>();
                for (Map.Entry<String, Object> p : props.entrySet()) {
                    String fieldName = fieldName(p.getKey());
                    if (!seen.add(fieldName)) continue;
                    addField(msg, fieldName, spec(asMap(p.getValue()), hint + "_" + fieldName));
                }
                return message("." + pkg + "." + msg.getName());
            }
            Object additional = schema.get("additionalProperties");
            if (additional instanceof Map<?, ?> m && !m.isEmpty()) {
                FieldSpec value = spec(asMap(m), hint + "Value");
                if (value.repeated || value.mapValue != null) return message(STRUCT_TYPE);
                return new FieldSpec(FieldDescriptorProto.Type.TYPE_MESSAGE, null, true, value);
            }
            if (Boolean.TRUE.equals(additional) || additional instanceof Map) {
                return new FieldSpec(FieldDescriptorProto.Type.TYPE_MESSAGE, null, true, message(VALUE_TYPE));
            }
            return message(STRUCT_TYPE);
        }

        /** Message for a component schema, built once; recursion returns the claimed name. */
        private DescriptorProto.Builder componentMessage(String componentName) {
            String protoName = typePrefix + sanitizeTypeName(componentName);
            DescriptorProto.Builder existing = messages.get(protoName);
            if (existing != null) return existing;
            DescriptorProto.Builder msg = claimMessage(protoName);
            if (!building.add(protoName)) return msg;
            try {
                Map<String, Object> schema = doc.componentSchemas().get(componentName);
                Map<String, Object> effective =
                        schema.get("allOf") instanceof List<?> parts ? mergeAllOf(parts) : schema;
                Map<String, Object> props = asMap(effective.get("properties"));
                if (props.isEmpty() && effective.get("enum") instanceof List<?> values) {
                    // A component that is a bare string enum: model as a message with a single value? No —
                    // fields referencing it get the enum directly; keep an empty message unused.
                    messages.remove(protoName);
                    enumType(protoName, values);
                    return msg;
                }
                Set<String> seen = new HashSet<>();
                for (Map.Entry<String, Object> p : props.entrySet()) {
                    String fieldName = fieldName(p.getKey());
                    if (!seen.add(fieldName)) continue;
                    addField(msg, fieldName, spec(asMap(p.getValue()), protoName + "_" + fieldName));
                }
                if (props.isEmpty() && !"object".equals(typeOf(effective))) {
                    // Primitive or array component: wrap in a single-field message so refs stay messages.
                    addField(msg, HttpOperationBinding.SCALAR_FIELD, spec(effective, protoName + "Value"));
                }
            } finally {
                building.remove(protoName);
            }
            return msg;
        }

        /** A {@code $ref} to a component: the enum itself for a bare string-enum component, else its message. */
        private FieldSpec componentEnumOrMessage(String componentName) {
            Map<String, Object> schema = doc.componentSchemas().get(componentName);
            if (schema != null) {
                Map<String, Object> effective =
                        schema.get("allOf") instanceof List<?> parts ? mergeAllOf(parts) : schema;
                String type = typeOf(effective);
                if (asMap(effective.get("properties")).isEmpty()
                        && effective.get("enum") instanceof List<?> values
                        && (type == null || type.equals("string"))) {
                    String protoName = typePrefix + sanitizeTypeName(componentName);
                    return new FieldSpec(
                            FieldDescriptorProto.Type.TYPE_ENUM,
                            "." + pkg + "." + enumType(protoName, values),
                            false,
                            null);
                }
            }
            return message("." + pkg + "." + componentMessage(componentName).getName());
        }

        private String enumType(String hint, List<?> values) {
            String name = sanitizeTypeName(hint);
            if (enums.containsKey(name)) return name;
            EnumDescriptorProto.Builder e = EnumDescriptorProto.newBuilder().setName(name);
            String prefix = upperSnake(name) + "_";
            e.addValue(EnumValueDescriptorProto.newBuilder()
                    .setName(prefix + "UNSPECIFIED")
                    .setNumber(0));
            int number = 1;
            Set<String> seen = new HashSet<>();
            Map<String, String> originals = new LinkedHashMap<>();
            for (Object v : values) {
                if (v == null) continue;
                String valueName =
                        prefix + sanitizeIdentifier(String.valueOf(v)).toUpperCase(Locale.ROOT);
                if (!seen.add(valueName)) continue;
                e.addValue(
                        EnumValueDescriptorProto.newBuilder().setName(valueName).setNumber(number++));
                originals.put(valueName, String.valueOf(v));
            }
            enums.put(name, e);
            enumOriginals.put(pkg + "." + name, originals);
            return name;
        }

        private void addBodyFields(
                DescriptorProto.Builder request, Map<String, Object> body, String hint, Set<String> requestFields) {
            Map<String, Object> effective = body;
            Object ref = body.get("$ref");
            String nestedHint = hint;
            if (ref instanceof String r) {
                String name = componentNameOf(r);
                effective = name != null ? doc.componentSchemas().getOrDefault(name, Map.of()) : Map.of();
                // Inline enums and objects of the component keep the component's type names, so the
                // request's `status` is the same enum as the response's, not a second copy.
                if (name != null && doc.componentSchemas().containsKey(name)) {
                    nestedHint = componentMessage(name).getName();
                }
            }
            if (effective.get("allOf") instanceof List<?> parts) effective = mergeAllOf(parts);
            Map<String, Object> props = asMap(effective.get("properties"));
            if (!props.isEmpty()) {
                for (Map.Entry<String, Object> p : props.entrySet()) {
                    String fieldName = fieldName(p.getKey());
                    if (!requestFields.add(fieldName)) continue; // a path/query parameter of the same name wins
                    addField(request, fieldName, spec(asMap(p.getValue()), nestedHint + "_" + fieldName));
                }
                return;
            }
            // Array or primitive body: a single field the dispatcher sends as the whole body.
            if (requestFields.add("body")) addField(request, "body", spec(body, hint + "Body"));
        }

        private void addField(DescriptorProto.Builder msg, String name, FieldSpec spec) {
            int number = msg.getFieldCount() + 1;
            FieldDescriptorProto.Builder f =
                    FieldDescriptorProto.newBuilder().setName(name).setNumber(number);
            if (spec.mapValue != null) {
                // map<string, V> is a repeated nested MapEntry message named <Field>Entry.
                String entryName = pascal(name) + "Entry";
                DescriptorProto.Builder entry = DescriptorProto.newBuilder()
                        .setName(entryName)
                        .setOptions(com.google.protobuf.DescriptorProtos.MessageOptions.newBuilder()
                                .setMapEntry(true))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("key")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL));
                FieldDescriptorProto.Builder value = FieldDescriptorProto.newBuilder()
                        .setName("value")
                        .setNumber(2)
                        .setType(spec.mapValue.type)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
                if (spec.mapValue.typeName != null) value.setTypeName(spec.mapValue.typeName);
                entry.addField(value);
                msg.addNestedType(entry);
                f.setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("." + pkg + "." + msg.getName() + "." + entryName)
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED);
            } else {
                f.setType(spec.type)
                        .setLabel(
                                spec.repeated
                                        ? FieldDescriptorProto.Label.LABEL_REPEATED
                                        : FieldDescriptorProto.Label.LABEL_OPTIONAL);
                if (spec.typeName != null) f.setTypeName(spec.typeName);
            }
            msg.addField(f);
        }

        private DescriptorProto.Builder claimMessage(String name) {
            String protoName = sanitizeTypeName(name);
            return messages.computeIfAbsent(
                    protoName, n -> DescriptorProto.newBuilder().setName(n));
        }

        private Map<String, Object> mergeAllOf(List<?> parts) {
            Map<String, Object> merged = new LinkedHashMap<>();
            Map<String, Object> props = new LinkedHashMap<>();
            for (Object part : parts) {
                Map<String, Object> p = asMap(part);
                if (p.get("$ref") instanceof String r) {
                    String name = componentNameOf(r);
                    p = name != null ? doc.componentSchemas().getOrDefault(name, Map.of()) : Map.of();
                    if (p.get("allOf") instanceof List<?> nested) p = mergeAllOf(nested);
                }
                props.putAll(asMap(p.get("properties")));
                p.forEach((k, v) -> {
                    if (!k.equals("properties") && !k.equals("allOf")) merged.putIfAbsent(k, v);
                });
            }
            if (!props.isEmpty()) {
                merged.put("properties", props);
                merged.putIfAbsent("type", "object");
            }
            return merged;
        }

        private String uniqueMethodName(DownstreamOpenApiDocument.Operation op, Set<String> taken) {
            String base = op.hasOperationId()
                    ? sanitizeIdentifier(op.operationId())
                    : sanitizeIdentifier(op.httpMethod().toLowerCase(Locale.ROOT) + "_" + op.path());
            String name = base;
            int i = 2;
            while (!taken.add(name)) name = base + i++;
            return name;
        }
    }

    // ---- naming -------------------------------------------------------------

    private static String typeOf(Map<String, Object> schema) {
        Object t = schema.get("type");
        if (t instanceof String s) return s;
        if (t instanceof List<?> l) { // 3.1: ["string", "null"]
            for (Object o : l) if (o != null && !"null".equals(o)) return String.valueOf(o);
        }
        return null;
    }

    private static String componentNameOf(String ref) {
        String prefix = "#/components/schemas/";
        return ref.startsWith(prefix) && ref.length() > prefix.length() ? ref.substring(prefix.length()) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** Proto field name: the property name with invalid characters replaced, never starting with a digit. */
    static String fieldName(String property) {
        String s = sanitizeIdentifier(property);
        return s.isEmpty() ? "field" : s;
    }

    static String sanitizeIdentifier(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) && c < 128 ? c : '_');
        }
        String out = sb.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (out.isEmpty()) return "x";
        if (Character.isDigit(out.charAt(0))) out = "_" + out;
        return out;
    }

    /** {@code ItemStatus} → {@code ITEM_STATUS}; {@code Item_status} → {@code ITEM_STATUS}: the proto enum value prefix. */
    static String upperSnake(String name) {
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c) && prev != 0 && prev != '_' && !Character.isUpperCase(prev)) sb.append('_');
            sb.append(Character.toUpperCase(c));
            prev = c;
        }
        return sb.toString().replaceAll("_+", "_");
    }

    static String sanitizeTypeName(String s) {
        String id = sanitizeIdentifier(s);
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    /** {@code inventory-service} → {@code InventoryService}; {@code getItem} → {@code GetItem}. */
    static String pascal(String s) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : s.toCharArray()) {
            if (!Character.isLetterOrDigit(c) || c >= 128) {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        String out = sb.toString();
        if (out.isEmpty()) return "X";
        if (Character.isDigit(out.charAt(0))) out = "X" + out;
        return out;
    }
}
