package dev.appiaworks.shiguchi.reflection;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.grpc.ManagedChannel;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around the gRPC server reflection API (v1).
 *
 * Workflow for dynamic mode:
 *  1. listServices()          -> fully-qualified service names
 *  2. resolveService(name)    -> FileDescriptorSet for that service (with transitive deps)
 *  3. DynamicRouteLoader parses descriptors, extracts google.api.http +
 *     shiguchi.api.graphql options, and registers RouteDefinitions.
 *
 * The recursive dep-fetching pattern is adopted from
 * github.com/grpc-ecosystem/polyglot: a single bidirectional streaming RPC
 * is kept open; for each received FileDescriptorProto we inspect its
 * getDependencyList() and fire additional setFileByFilename() requests for
 * any dep not yet fetched. An AtomicInteger tracks outstanding requests so
 * we know when to close the stream.
 *
 * All calls carry explicit deadlines to prevent indefinite hangs during startup.
 */
public class GrpcReflectionClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcReflectionClient.class);

    private static final long LIST_DEADLINE_SECONDS = 5;
    private static final long RESOLVE_DEADLINE_SECONDS = 10;
    private static final long FUTURE_TIMEOUT_SECONDS = 12;

    private final ManagedChannel channel;

    public GrpcReflectionClient(ManagedChannel channel) {
        this.channel = channel;
    }

    /** Returns all fully-qualified service names (grpc.reflection.* excluded). */
    public List<String> listServices() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        List<String> services = new ArrayList<>();
        var stub = ServerReflectionGrpc.newStub(channel).withDeadlineAfter(LIST_DEADLINE_SECONDS, TimeUnit.SECONDS);

        StreamObserver<ServerReflectionRequest>[] requestObserver = new StreamObserver[1];
        requestObserver[0] = stub.serverReflectionInfo(new StreamObserver<>() {
            @Override
            public void onNext(ServerReflectionResponse r) {
                if (r.hasListServicesResponse()) {
                    r.getListServicesResponse().getServiceList().stream()
                            .map(s -> s.getName())
                            .filter(n -> !n.startsWith("grpc.reflection"))
                            .forEach(services::add);
                }
            }

            @Override
            public void onError(Throwable t) {
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                future.complete(services);
            }
        });

        requestObserver[0].onNext(
                ServerReflectionRequest.newBuilder().setListServices("").build());
        requestObserver[0].onCompleted();
        return future.get(LIST_DEADLINE_SECONDS + 2, TimeUnit.SECONDS);
    }

    /**
     * Returns a FileDescriptorSet for the given service, including all transitive file deps.
     *
     * Follows the polyglot pattern:
     * - Outstanding request counter starts at 1 (the initial symbol lookup).
     * - Each received FileDescriptorProto decrements the counter but also fires new
     *   setFileByFilename requests for any unseen dependency, incrementing the counter.
     * - When the counter reaches 0 the stream is closed and the future is resolved.
     */
    public FileDescriptorSet resolveService(String fullServiceName)
            throws ExecutionException, InterruptedException, TimeoutException {

        CompletableFuture<FileDescriptorSet> future = new CompletableFuture<>();
        Map<String, FileDescriptorProto> collected = new HashMap<>();
        Set<String> requested = new HashSet<>();
        AtomicInteger outstanding = new AtomicInteger(1);
        var stub = ServerReflectionGrpc.newStub(channel).withDeadlineAfter(RESOLVE_DEADLINE_SECONDS, TimeUnit.SECONDS);

        StreamObserver<ServerReflectionRequest>[] ref = new StreamObserver[1];
        ref[0] = stub.serverReflectionInfo(new StreamObserver<>() {
            @Override
            public void onNext(ServerReflectionResponse r) {
                if (r.hasFileDescriptorResponse()) {
                    for (var bytes : r.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                        FileDescriptorProto fdp;
                        // Parse with the extension registry — without it google.api.http /
                        // (shiguchi.graphql.*) method options arrive as unknown fields and every
                        // annotation-derived binding is silently dropped.
                        try {
                            fdp = FileDescriptorProto.parseFrom(bytes, GatewayExtensionRegistry.get());
                        } catch (Exception e) {
                            future.completeExceptionally(e);
                            return;
                        }

                        collected.put(fdp.getName(), fdp);

                        // Fire requests for any transitive dep not yet requested
                        for (String dep : fdp.getDependencyList()) {
                            if (requested.add(dep)) {
                                outstanding.incrementAndGet();
                                ref[0].onNext(ServerReflectionRequest.newBuilder()
                                        .setFileByFilename(dep)
                                        .build());
                            }
                        }
                    }
                }
                if (outstanding.decrementAndGet() == 0) {
                    ref[0].onCompleted();
                }
            }

            @Override
            public void onError(Throwable t) {
                future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                future.complete(FileDescriptorSet.newBuilder()
                        .addAllFile(collected.values())
                        .build());
            }
        });

        requested.add(fullServiceName);
        ref[0].onNext(ServerReflectionRequest.newBuilder()
                .setFileContainingSymbol(fullServiceName)
                .build());

        return future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
