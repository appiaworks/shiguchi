package dev.appiaworks.shiguchi.grpc;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.reflection.v1.ErrorResponse;
import io.grpc.reflection.v1.FileDescriptorResponse;
import io.grpc.reflection.v1.ListServiceResponse;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.reflection.v1.ServiceResponse;
import io.grpc.stub.StreamObserver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * gRPC reflection service that aggregates descriptors from all downstream services.
 *
 * Handles listServices, fileContainingSymbol, and fileByFilename requests by
 * combining state from all ServiceEndpointContexts in GrpcRouteRegistry.
 * Duplicate proto files (e.g. google/protobuf/timestamp.proto) are deduplicated
 * by filename — the first context that provides the file wins.
 *
 * Registered as an explicit service on the gRPC proxy server so tools like
 * grpcurl, Postman, and grpcui can discover the full combined schema.
 */
@Component
public class GrpcReflectionAggregator extends ServerReflectionGrpc.ServerReflectionImplBase {

    private static final int STATUS_NOT_FOUND = io.grpc.Status.Code.NOT_FOUND.value();
    private static final int STATUS_UNIMPLEMENTED = io.grpc.Status.Code.UNIMPLEMENTED.value();

    private final GrpcRouteRegistry registry;

    public GrpcReflectionAggregator(GrpcRouteRegistry registry) {
        this.registry = registry;
    }

    @Override
    public StreamObserver<ServerReflectionRequest> serverReflectionInfo(
            StreamObserver<ServerReflectionResponse> responseObserver) {

        return new StreamObserver<>() {
            @Override
            public void onNext(ServerReflectionRequest request) {
                ServerReflectionResponse response = switch (request.getMessageRequestCase()) {
                    case LIST_SERVICES -> handleListServices(request);
                    case FILE_CONTAINING_SYMBOL -> handleFileContainingSymbol(request);
                    case FILE_BY_FILENAME -> handleFileByFilename(request);
                    default ->
                        errorResponse(
                                request,
                                STATUS_UNIMPLEMENTED,
                                "Request type not supported: " + request.getMessageRequestCase());
                };
                responseObserver.onNext(response);
            }

            @Override
            public void onError(Throwable t) {
                responseObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    // -------------------------------------------------------------------------

    private ServerReflectionResponse handleListServices(ServerReflectionRequest request) {
        List<ServiceResponse> services = registry.allContexts().stream()
                .flatMap(ctx -> ctx.getServiceByFqn().keySet().stream())
                .distinct()
                .map(fqn -> ServiceResponse.newBuilder().setName(fqn).build())
                .collect(Collectors.toList());

        return ServerReflectionResponse.newBuilder()
                .setValidHost(request.getHost())
                .setOriginalRequest(request)
                .setListServicesResponse(
                        ListServiceResponse.newBuilder().addAllService(services).build())
                .build();
    }

    private ServerReflectionResponse handleFileContainingSymbol(ServerReflectionRequest request) {
        String symbol = request.getFileContainingSymbol();

        for (ServiceEndpointContext ctx : registry.allContexts()) {
            if (ctx.getRawDescriptorSet() == null) continue;

            boolean found = ctx.getServiceByFqn().containsKey(symbol)
                    || ctx.getFileDescriptors().values().stream()
                            .anyMatch(fd -> fd.getMessageTypes().stream()
                                            .anyMatch(mt -> mt.getFullName().equals(symbol))
                                    || fd.getEnumTypes().stream()
                                            .anyMatch(et -> et.getFullName().equals(symbol)));

            if (found) {
                return fileDescriptorSetResponse(request, ctx);
            }
        }

        return errorResponse(request, STATUS_NOT_FOUND, "Symbol not found: " + symbol);
    }

    private ServerReflectionResponse handleFileByFilename(ServerReflectionRequest request) {
        String filename = request.getFileByFilename();
        FileDescriptorProto fdp = collectAllFiles().get(filename);
        if (fdp == null) {
            return errorResponse(request, STATUS_NOT_FOUND, "File not found: " + filename);
        }
        return ServerReflectionResponse.newBuilder()
                .setValidHost(request.getHost())
                .setOriginalRequest(request)
                .setFileDescriptorResponse(FileDescriptorResponse.newBuilder()
                        .addFileDescriptorProto(fdp.toByteString())
                        .build())
                .build();
    }

    // -------------------------------------------------------------------------

    private ServerReflectionResponse fileDescriptorSetResponse(
            ServerReflectionRequest request, ServiceEndpointContext ctx) {

        FileDescriptorResponse.Builder builder = FileDescriptorResponse.newBuilder();
        for (FileDescriptorProto fdp : ctx.getRawDescriptorSet().getFileList()) {
            builder.addFileDescriptorProto(fdp.toByteString());
        }
        return ServerReflectionResponse.newBuilder()
                .setValidHost(request.getHost())
                .setOriginalRequest(request)
                .setFileDescriptorResponse(builder.build())
                .build();
    }

    /** All FileDescriptorProtos across all contexts, deduplicated by proto filename. */
    private Map<String, FileDescriptorProto> collectAllFiles() {
        Map<String, FileDescriptorProto> result = new LinkedHashMap<>();
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            if (ctx.getRawDescriptorSet() != null) {
                for (FileDescriptorProto fdp : ctx.getRawDescriptorSet().getFileList()) {
                    result.putIfAbsent(fdp.getName(), fdp);
                }
            }
        }
        return result;
    }

    private static ServerReflectionResponse errorResponse(ServerReflectionRequest request, int code, String message) {
        return ServerReflectionResponse.newBuilder()
                .setValidHost(request.getHost())
                .setOriginalRequest(request)
                .setErrorResponse(ErrorResponse.newBuilder()
                        .setErrorCode(code)
                        .setErrorMessage(message)
                        .build())
                .build();
    }
}
