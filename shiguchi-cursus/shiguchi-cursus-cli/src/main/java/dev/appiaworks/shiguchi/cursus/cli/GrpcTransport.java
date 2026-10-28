package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import dev.appiaworks.shiguchi.proto.cursus.AckRequest;
import dev.appiaworks.shiguchi.proto.cursus.PushEvent;
import dev.appiaworks.shiguchi.proto.cursus.PushServiceGrpc;
import dev.appiaworks.shiguchi.proto.cursus.Qos;
import dev.appiaworks.shiguchi.proto.cursus.SubscribeRequest;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.stub.MetadataUtils;
import java.util.Base64;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/** {@code PushService.Subscribe} over gRPC, with inline {@code Ack}s. */
final class GrpcTransport implements Transport, AutoCloseable {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> CLOSE_REASON =
            Metadata.Key.of("push-close-reason", Metadata.ASCII_STRING_MARSHALLER);
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

    private final ManagedChannel channel;
    private final TokenSource tokens;

    GrpcTransport(ManagedChannel channel, TokenSource tokens) {
        this.channel = channel;
        this.tokens = tokens;
    }

    /** A channel to the gateway's gRPC port; TLS when the gateway URL is https. */
    static GrpcTransport connect(Settings settings) {
        // The fat jar's merged META-INF/services lacks grpc-core's entries (the DNS resolver among
        // them), so register the one provider a host:port target needs, and name its scheme.
        // Harmless when the service files are intact: the registry keys providers by scheme.
        io.grpc.NameResolverRegistry.getDefaultRegistry().register(new io.grpc.internal.DnsNameResolverProvider());
        io.grpc.LoadBalancerRegistry.getDefaultRegistry()
                .register(new io.grpc.internal.PickFirstLoadBalancerProvider());
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget("dns:///" + settings.grpcAuthority());
        if (settings.tls()) {
            try {
                var ssl = GrpcSslContexts.forClient();
                if (settings.insecure()) ssl.trustManager(InsecureTrustManagerFactory.INSTANCE);
                builder.sslContext(ssl.build());
            } catch (SSLException e) {
                throw new CliException(CliException.TRANSPORT, "cannot set up TLS for gRPC: " + e.getMessage());
            }
        } else {
            builder.usePlaintext();
        }
        return new GrpcTransport(builder.build(), settings.token());
    }

    private PushServiceGrpc.PushServiceBlockingStub stub() {
        Metadata metadata = new Metadata();
        String token = tokens.current();
        if (token != null) metadata.put(AUTHORIZATION, "Bearer " + token);
        return PushServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @Override
    public End stream(SubRequest request, EventSink sink, Lifecycle lifecycle) {
        SubscribeRequest.Builder builder = SubscribeRequest.newBuilder().addAllTopics(request.topics());
        if (request.after() != null) builder.setAfter(request.after());
        if (request.session() != null) builder.setSession(request.session());
        if (request.origin() != null) builder.setOrigin(request.origin());
        if (request.group() != null) builder.setGroup(request.group());
        if (request.member() != null) builder.setMember(request.member());
        if (request.qos() != null) builder.setQos(Qos.valueOf(request.qos().toUpperCase()));
        PushServiceGrpc.PushServiceBlockingStub stub = stub();
        Context.CancellableContext context = Context.current().withCancellation();
        lifecycle.onStop(() -> context.cancel(null));
        try {
            return context.call(() -> {
                Iterator<PushEvent> events = stub.subscribe(builder.build());
                while (events.hasNext()) {
                    PushEvent event = events.next();
                    String signal = event.getAttributesMap().get("push.signal");
                    if (signal != null) {
                        // reset / overflow: the gateway talking, not an event (no id to acknowledge)
                        sink.control(signal, toNode(event));
                        continue;
                    }
                    boolean more = sink.event(toNode(event));
                    if (request.ack()) ack(stub, request, event);
                    if (!more) return End.of(End.Kind.DONE, null);
                }
                return End.of(End.Kind.EOF, null);
            });
        } catch (StatusRuntimeException e) {
            return translate(e, lifecycle);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CliException(CliException.TRANSPORT, Http.describe(e), true, false);
        } finally {
            lifecycle.onStop(null);
            context.cancel(null);
        }
    }

    private static void ack(PushServiceGrpc.PushServiceBlockingStub stub, SubRequest request, PushEvent event) {
        AckRequest.Builder ack =
                AckRequest.newBuilder().setTopic(event.getTopic()).setId(event.getId());
        if (request.group() != null) ack.setGroup(request.group());
        if (request.member() != null) ack.setMember(request.member());
        if (request.session() != null) ack.setSession(request.session());
        stub.ack(ack.build());
    }

    private static End translate(StatusRuntimeException e, Lifecycle lifecycle) {
        Status.Code code = e.getStatus().getCode();
        String reason = e.getTrailers() == null ? null : e.getTrailers().get(CLOSE_REASON);
        String detail = e.getStatus().getDescription() == null
                ? code.name()
                : code + ": " + e.getStatus().getDescription();
        if (lifecycle.isStopped() && code == Status.Code.CANCELLED) return End.of(End.Kind.DONE, null);
        switch (code) {
            case UNAVAILABLE:
                if (reason != null) return End.of(End.Kind.CLOSED, reason);
                throw new CliException(CliException.TRANSPORT, "gRPC " + detail, true, false);
            case RESOURCE_EXHAUSTED:
                return End.of(End.Kind.OVERFLOW, reason == null ? "resource_exhausted" : reason);
            case UNAUTHENTICATED:
                throw new CliException(CliException.AUTH, "gRPC " + detail, false, true);
            case PERMISSION_DENIED:
                throw new CliException(CliException.AUTH, "gRPC " + detail, false, false);
            case NOT_FOUND:
            case UNIMPLEMENTED:
                throw new CliException(CliException.NOT_SUPPORTED, "gRPC " + detail);
            default:
                throw new CliException(CliException.TRANSPORT, "gRPC " + detail);
        }
    }

    static ObjectNode toNode(PushEvent event) {
        ObjectNode node = Http.JSON.createObjectNode();
        node.put("topic", event.getTopic());
        node.put("id", event.getId());
        try {
            switch (event.getPayloadCase()) {
                case JSON -> node.set("data", Http.JSON.readTree(PRINTER.print(event.getJson())));
                case MESSAGE -> node.set("message", any(event.getMessage()));
                default -> node.putNull("data");
            }
        } catch (java.io.IOException e) {
            node.putNull("data");
        }
        if (event.getAttributesCount() > 0) {
            ObjectNode attributes = node.putObject("attributes");
            event.getAttributesMap().forEach(attributes::put);
        }
        if (!event.getKey().isEmpty()) node.put("key", event.getKey());
        if (event.hasTime()) node.put("time", Timestamps.toString(event.getTime()));
        return node;
    }

    private static JsonNode any(Any any) throws java.io.IOException {
        try {
            return Http.JSON.readTree(PRINTER.print(any));
        } catch (InvalidProtocolBufferException e) {
            ObjectNode raw = Http.JSON.createObjectNode();
            raw.put("@type", any.getTypeUrl());
            raw.put("value", Base64.getEncoder().encodeToString(any.getValue().toByteArray()));
            return raw;
        }
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
