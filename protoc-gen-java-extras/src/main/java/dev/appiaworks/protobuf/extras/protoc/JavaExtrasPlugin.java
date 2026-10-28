package dev.appiaworks.protobuf.extras.protoc;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.Edition;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse.Feature;
import dev.appiaworks.protobuf.extras.protoc.AccessorEmitter.MappedField;
import dev.appiaworks.protobuf.extras.protoc.AccessorEmitter.Shape;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code protoc-gen-javaextras}: a protoc plugin that adds Java-typed accessors to the classes
 * protoc's own Java generator writes.
 *
 * <p>A {@code google.type.Date session_date} field gains {@code getSessionDateAsLocalDate()} and
 * {@code setSessionDate(LocalDate)} beside the generated {@code getSessionDate()} and
 * {@code setSessionDate(Date)}. The conversions are those of {@code ProtoTypes} in
 * protobuf-java-extras, which the generated code calls, so that library must be on the classpath
 * of whatever compiles the output.
 *
 * <p>Every message also gains {@code writeJson(JsonGenerator)}, and implements
 * {@code JsonWritable}: the message as proto JSON through its own getters, which {@code ProtoJson}
 * and the gateway then print through instead of generating a writer at run time. Its Builder
 * gains {@code mergeJson(JsonParser)} and implements {@code JsonReadable}, the same for parsing.
 * {@code json=off} leaves both out.
 *
 * <p>It works through protoc's insertion points ({@code class_scope:<type>},
 * {@code builder_scope:<type>}, {@code message_implements:<type>} and
 * {@code builder_implements:<type>}), so it must run in the same
 * protoc invocation as the Java generator and write to the same output directory.
 */
public final class JavaExtrasPlugin {

    private JavaExtrasPlugin() {}

    public static void main(String[] args) throws IOException {
        CodeGeneratorRequest request = CodeGeneratorRequest.parseFrom(System.in);
        CodeGeneratorResponse response;
        try {
            response = generate(request);
        } catch (IllegalArgumentException e) {
            // A bad option is the user's to fix: protoc prints this and fails the build.
            response = declareFeatures(CodeGeneratorResponse.newBuilder())
                    .setError("protoc-gen-javaextras: " + e.getMessage())
                    .build();
        }
        response.writeTo(System.out);
        System.out.flush();
    }

    static CodeGeneratorResponse generate(CodeGeneratorRequest request) {
        PluginOptions options = PluginOptions.parse(request.getParameter());
        List<TypeMapping> mappings = options.resolve();
        Map<String, List<TypeMapping>> byProtoType = new HashMap<>();
        for (TypeMapping mapping : mappings) {
            byProtoType
                    .computeIfAbsent(mapping.protoType(), k -> new ArrayList<>())
                    .add(mapping);
        }

        CodeGeneratorResponse.Builder response = declareFeatures(CodeGeneratorResponse.newBuilder());
        Map<String, FileDescriptor> built = options.json() ? buildDescriptors(request) : Map.of();

        for (FileDescriptorProto file : request.getProtoFileList()) {
            if (!request.getFileToGenerateList().contains(file.getName())) continue;
            FileDescriptor descriptors = built.get(file.getName());
            for (DescriptorProto message : file.getMessageTypeList()) {
                String protoName =
                        file.getPackage().isEmpty() ? message.getName() : file.getPackage() + "." + message.getName();
                Descriptor descriptor =
                        descriptors == null ? null : descriptors.findMessageTypeByName(message.getName());
                emit(
                        message,
                        descriptor,
                        protoName,
                        JavaNames.javaFile(file, message.getName()),
                        byProtoType,
                        response);
            }
        }
        return response.build();
    }

    /**
     * The request's files as descriptors, by file name, for what the JSON writer needs to know
     * and a {@code DescriptorProto} does not say: a field's presence, its JSON name, whether an
     * enum is open. protoc lists every file after the files it imports; a request that does not
     * (a hand-built one) still builds, with placeholders for what is missing, and a file that
     * cannot be built gets no writers.
     */
    private static Map<String, FileDescriptor> buildDescriptors(CodeGeneratorRequest request) {
        Map<String, FileDescriptor> built = new HashMap<>();
        for (FileDescriptorProto file : request.getProtoFileList()) {
            List<FileDescriptor> dependencies = new ArrayList<>();
            for (String name : file.getDependencyList()) {
                FileDescriptor dependency = built.get(name);
                if (dependency != null) dependencies.add(dependency);
            }
            try {
                built.put(
                        file.getName(),
                        FileDescriptor.buildFrom(file, dependencies.toArray(FileDescriptor[]::new), true));
            } catch (DescriptorValidationException e) {
                // Accessors need no descriptors; the file's messages carry no writer.
            }
        }
        return built;
    }

    /** Declared on every response, an error included, so protoc reports the error and nothing else. */
    private static CodeGeneratorResponse.Builder declareFeatures(CodeGeneratorResponse.Builder response) {
        return response.setSupportedFeatures(
                        Feature.FEATURE_PROTO3_OPTIONAL_VALUE | Feature.FEATURE_SUPPORTS_EDITIONS_VALUE)
                .setMinimumEdition(Edition.EDITION_PROTO2_VALUE)
                .setMaximumEdition(Edition.EDITION_2023_VALUE);
    }

    /**
     * @param descriptor the message's built descriptor, for the JSON writer; null when none is
     *     wanted or could be built
     */
    private static void emit(
            DescriptorProto message,
            Descriptor descriptor,
            String protoName,
            String javaFile,
            Map<String, List<TypeMapping>> mappings,
            CodeGeneratorResponse.Builder response) {
        // protoc generates no class for a map entry, so there is nothing to insert into.
        if (message.getOptions().getMapEntry()) return;

        StringBuilder classScope = new StringBuilder();
        StringBuilder builderScope = new StringBuilder();
        for (FieldDescriptorProto field : message.getFieldList()) {
            for (MappedField mapped : mappedFields(message, protoName, field, mappings)) {
                String getters = AccessorEmitter.getters(mapped);
                classScope.append(getters);
                builderScope.append(getters).append(AccessorEmitter.setters(mapped));
            }
        }
        boolean json = descriptor != null && JsonWriterEmitter.canWrite(descriptor);
        if (json) {
            classScope.append(JsonWriterEmitter.writeJson(descriptor)).append(JsonReaderEmitter.holder());
            builderScope.append(JsonReaderEmitter.mergeJson(descriptor));
        }
        if (classScope.length() > 0) {
            response.addFileBuilder()
                    .setName(javaFile)
                    .setInsertionPoint("class_scope:" + protoName)
                    .setContent(classScope.toString());
        }
        if (builderScope.length() > 0) {
            response.addFileBuilder()
                    .setName(javaFile)
                    .setInsertionPoint("builder_scope:" + protoName)
                    .setContent(builderScope.toString());
        }
        if (json) {
            // protoc places this in the class's implements list, before the OrBuilder interface.
            response.addFileBuilder()
                    .setName(javaFile)
                    .setInsertionPoint("message_implements:" + protoName)
                    .setContent("dev.appiaworks.protobuf.extras.JsonWritable,\n");
            response.addFileBuilder()
                    .setName(javaFile)
                    .setInsertionPoint("builder_implements:" + protoName)
                    .setContent("dev.appiaworks.protobuf.extras.JsonReadable,\n");
        }
        for (DescriptorProto nested : message.getNestedTypeList()) {
            Descriptor nestedDescriptor = descriptor == null ? null : descriptor.findNestedTypeByName(nested.getName());
            emit(nested, nestedDescriptor, protoName + "." + nested.getName(), javaFile, mappings, response);
        }
    }

    /** The mappings that apply to a field, with how the field holds its values. */
    private static List<MappedField> mappedFields(
            DescriptorProto message,
            String protoName,
            FieldDescriptorProto field,
            Map<String, List<TypeMapping>> mappings) {
        if (field.getType() != Type.TYPE_MESSAGE && field.getType() != Type.TYPE_ENUM) return List.of();
        boolean repeated = field.getLabel() == Label.LABEL_REPEATED;
        String accessor = JavaNames.accessorName(message, field);

        DescriptorProto entry = repeated ? mapEntry(message, protoName, field) : null;
        if (entry != null) {
            FieldDescriptorProto key = entry.getField(0);
            FieldDescriptorProto value = entry.getField(1);
            if (value.getType() != Type.TYPE_MESSAGE && value.getType() != Type.TYPE_ENUM) return List.of();
            List<MappedField> result = new ArrayList<>();
            for (TypeMapping mapping : mappings.getOrDefault(typeName(value), List.of())) {
                result.add(new MappedField(field, accessor, mapping, Shape.MAP, key.getType()));
            }
            return result;
        }

        List<MappedField> result = new ArrayList<>();
        for (TypeMapping mapping : mappings.getOrDefault(typeName(field), List.of())) {
            result.add(new MappedField(field, accessor, mapping, repeated ? Shape.REPEATED : Shape.SINGULAR, null));
        }
        return result;
    }

    /** The synthetic entry message of a map field, or null when the field is not a map. */
    private static DescriptorProto mapEntry(DescriptorProto message, String protoName, FieldDescriptorProto field) {
        if (field.getType() != Type.TYPE_MESSAGE) return null;
        String prefix = protoName + ".";
        String type = typeName(field);
        if (!type.startsWith(prefix)) return null;
        String simple = type.substring(prefix.length());
        for (DescriptorProto nested : message.getNestedTypeList()) {
            if (nested.getName().equals(simple) && nested.getOptions().getMapEntry()) return nested;
        }
        return null;
    }

    /** A field's type as a full proto name without the leading dot protoc writes. */
    private static String typeName(FieldDescriptorProto field) {
        String name = field.getTypeName();
        return name.startsWith(".") ? name.substring(1) : name;
    }
}
