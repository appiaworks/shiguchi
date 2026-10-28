package dev.appiaworks.shiguchi.serde;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.serde.JsonPrintOptions;
import dev.appiaworks.shiguchi.grpc.GrpcDescriptorCache;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.stub.ClientCalls;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Handles dynamic proto serde for DYNAMIC gateway mode.
 *
 * Pipeline (polyglot-inspired, github.com/grpc-ecosystem/polyglot):
 *  1. buildFileDescriptors(FileDescriptorSet)  — topological dep sort, then
 *     FileDescriptor.buildFrom() for each proto file.
 *  2. buildTypeRegistry(descriptors)           — needed by JsonFormat for Any fields.
 *  3. jsonToRequest(descriptor, json)          — JsonFormat.parser -> DynamicMessage.
 *  4. invoke*(channel, service, method, req)   — ClientCalls with DynamicMessageMarshaller.
 *  5. responseToJson(DynamicMessage)           — JsonFormat.printer -> JSON string (ProtoJsonText).
 */
@Component
public class DynamicProtoSerde {

    private final GrpcDescriptorCache descriptorCache;

    public DynamicProtoSerde(GrpcDescriptorCache descriptorCache) {
        this.descriptorCache = descriptorCache;
    }

    /**
     * Reconstructs FileDescriptor instances from a FileDescriptorSet returned by gRPC reflection.
     * Uses recursive resolution respecting proto import dependency order.
     *
     * @return map of proto file name -> FileDescriptor
     */
    public Map<String, FileDescriptor> buildFileDescriptors(FileDescriptorSet fds) throws Exception {
        Map<String, FileDescriptorProto> protoByName = new HashMap<>();
        for (FileDescriptorProto fdp : fds.getFileList()) {
            protoByName.put(fdp.getName(), fdp);
        }
        Map<String, FileDescriptor> built = new HashMap<>();
        for (FileDescriptorProto fdp : fds.getFileList()) {
            buildRecursive(fdp, protoByName, built);
        }
        return built;
    }

    private void buildRecursive(
            FileDescriptorProto target, Map<String, FileDescriptorProto> protoByName, Map<String, FileDescriptor> built)
            throws Exception {

        if (built.containsKey(target.getName())) return;

        List<FileDescriptor> deps = new ArrayList<>();
        for (String dep : target.getDependencyList()) {
            FileDescriptorProto depProto = protoByName.get(dep);
            if (depProto != null) {
                buildRecursive(depProto, protoByName, built);
                deps.add(built.get(dep));
            }
        }
        FileDescriptor fd = FileDescriptor.buildFrom(target, deps.toArray(new FileDescriptor[0]));
        built.put(target.getName(), fd);
    }

    /**
     * Builds a JsonFormat TypeRegistry from all message types in the descriptor set.
     * Required for correct serialisation of google.protobuf.Any fields (polyglot pattern).
     */
    public JsonFormat.TypeRegistry buildTypeRegistry(Collection<FileDescriptor> fileDescriptors) {
        JsonFormat.TypeRegistry.Builder builder = JsonFormat.TypeRegistry.newBuilder();
        for (FileDescriptor fd : fileDescriptors) {
            fd.getMessageTypes().forEach(builder::add);
        }
        return builder.build();
    }

    /**
     * Parses a JSON string into a DynamicMessage for the given proto message descriptor.
     */
    public DynamicMessage jsonToRequest(Descriptor messageDescriptor, JsonFormat.TypeRegistry typeRegistry, String json)
            throws InvalidProtocolBufferException {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(messageDescriptor);
        JsonFormat.parser()
                .usingTypeRegistry(typeRegistry)
                .ignoringUnknownFields()
                .merge(json, builder);
        return builder.build();
    }

    /**
     * Serialises a DynamicMessage to JSON.
     */
    public String responseToJson(DynamicMessage message, JsonFormat.TypeRegistry typeRegistry)
            throws InvalidProtocolBufferException {
        return ProtoJsonText.print(
                JsonFormat.printer()
                        .usingTypeRegistry(typeRegistry)
                        .includingDefaultValueFields()
                        .omittingInsignificantWhitespace(),
                message);
    }

    /** {@link #responseToJson(DynamicMessage, JsonFormat.TypeRegistry)} with a service's print options. */
    public String responseToJson(DynamicMessage message, JsonFormat.TypeRegistry typeRegistry, JsonPrintOptions options)
            throws InvalidProtocolBufferException {
        if (options == null || options.isDefault()) return responseToJson(message, typeRegistry);
        JsonFormat.Printer printer =
                JsonFormat.printer().usingTypeRegistry(typeRegistry).omittingInsignificantWhitespace();
        if (!options.omitDefaultValues()) printer = printer.includingDefaultValueFields();
        if (options.preserveProtoFieldNames()) printer = printer.preservingProtoFieldNames();
        if (options.enumsAsInts()) printer = printer.printingEnumsAsInts();
        return ProtoJsonText.print(printer, message);
    }

    /**
     * Unary gRPC call using DynamicMessage marshalling (no compiled stubs required).
     * Used during startup serde pre-compilation — blocking by design.
     */
    public String invokeUnary(
            Channel channel,
            ServiceDescriptor serviceDescriptor,
            MethodDescriptor methodDescriptor,
            DynamicMessage request,
            JsonFormat.TypeRegistry typeRegistry)
            throws Exception {

        var grpcMethod = buildGrpcMethodDescriptor(serviceDescriptor, methodDescriptor, MethodType.UNARY);
        var call = channel.newCall(grpcMethod, CallOptions.DEFAULT);
        DynamicMessage response = ClientCalls.blockingUnaryCall(call, request);
        return responseToJson(response, typeRegistry);
    }

    /**
     * Server-streaming gRPC call, responses collected into a JSON array.
     * Used during startup serde pre-compilation — blocking by design.
     */
    public String invokeServerStreaming(
            Channel channel,
            ServiceDescriptor serviceDescriptor,
            MethodDescriptor methodDescriptor,
            DynamicMessage request,
            JsonFormat.TypeRegistry typeRegistry)
            throws Exception {

        var grpcMethod = buildGrpcMethodDescriptor(serviceDescriptor, methodDescriptor, MethodType.SERVER_STREAMING);
        var call = channel.newCall(grpcMethod, CallOptions.DEFAULT);
        Iterator<DynamicMessage> responses = ClientCalls.blockingServerStreamingCall(call, request);

        var sb = new StringBuilder("[");
        boolean first = true;
        while (responses.hasNext()) {
            if (!first) sb.append(",");
            sb.append(responseToJson(responses.next(), typeRegistry));
            first = false;
        }
        return sb.append("]").toString();
    }

    private io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> buildGrpcMethodDescriptor(
            ServiceDescriptor serviceDescriptor, MethodDescriptor methodDescriptor, MethodType methodType) {

        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(methodType)
                .setFullMethodName(io.grpc.MethodDescriptor.generateFullMethodName(
                        serviceDescriptor.getFullName(), methodDescriptor.getName()))
                .setRequestMarshaller(descriptorCache.marshallerFor(methodDescriptor.getInputType()))
                .setResponseMarshaller(descriptorCache.marshallerFor(methodDescriptor.getOutputType()))
                .build();
    }

    /**
     * Derives the gRPC method type from the proto descriptor flags.
     * Mirrors the polyglot approach: check clientStreaming + serverStreaming booleans.
     */
    public static MethodType resolveMethodType(MethodDescriptor methodDescriptor) {
        boolean clientStreaming = methodDescriptor.toProto().getClientStreaming();
        boolean serverStreaming = methodDescriptor.toProto().getServerStreaming();
        if (clientStreaming && serverStreaming) return MethodType.BIDI_STREAMING;
        if (clientStreaming) return MethodType.CLIENT_STREAMING;
        if (serverStreaming) return MethodType.SERVER_STREAMING;
        return MethodType.UNARY;
    }
}
