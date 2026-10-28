package dev.appiaworks.shiguchi.cursus;

import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.Metadata;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A served service's server-streaming method as a source ({@code type: GRPC_STREAM}): the
 * gateway opens {@code service/method} with {@code request} (JSON, empty by default) through its
 * dispatcher and turns every response message into a typed event; the topic comes from
 * {@code topic-template} over the message's scalar fields ({@code orders:{order_id}}). A message
 * whose fields do not fill the template is dead-lettered. The stream is reopened with a backoff
 * when it ends.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public final class GrpcStreamPushSource implements PushSource {

    private static final Logger log = LoggerFactory.getLogger(GrpcStreamPushSource.class);

    /** A server-streaming call through the gateway, by service and method name. */
    @FunctionalInterface
    public interface Opener {
        Flux<DynamicMessage> open(
                ServiceEndpointContext ctx, String serviceFqn, String methodName, DynamicMessage request);
    }

    private final String name;
    private final PushProperties.Source config;
    private final TopicTemplate template;
    private final GrpcRouteRegistry registry;
    private final Opener opener;
    private final PushDlq dlq;

    public GrpcStreamPushSource(
            String name, PushProperties.Source config, GrpcRouteRegistry registry, Opener opener, PushDlq dlq) {
        this.name = name;
        this.config = config;
        this.template = TopicTemplate.parseTarget(config.getTopicTemplate());
        this.registry = registry;
        this.opener = opener;
        this.dlq = dlq;
        PushProperties.SourceGrpc grpc = config.getGrpc();
        if (grpc.getService() == null
                || grpc.getService().isBlank()
                || grpc.getMethod() == null
                || grpc.getMethod().isBlank()) {
            throw new IllegalStateException(
                    "shiguchi.cursus.sources[" + name + "]: grpc.service and grpc.method are required");
        }
        ServiceEndpointContext ctx = context();
        if (ctx == null) {
            throw new IllegalStateException("shiguchi.cursus.sources[" + name + "]: grpc.service '" + grpc.getService()
                    + "' is not a service the gateway serves");
        }
        MethodDescriptor md = method(ctx);
        request(ctx, md);
    }

    /** The opener over the gateway's dispatcher. */
    public static Opener opener(dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher dispatcher) {
        return (ctx, service, method, request) ->
                dispatcher.dispatchStreamingRequest(ctx, service, method, request, new Metadata());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean shared() {
        return config.isShared();
    }

    @Override
    public String describe() {
        return "GRPC_STREAM " + config.getGrpc().getService() + "/"
                + config.getGrpc().getMethod() + " -> " + template;
    }

    /** The template's namespace, declared by {@link PushSources} when it is not configured. */
    public TopicTemplate template() {
        return template;
    }

    /** The response message's full name: the namespace's payload type. */
    public String payloadType() {
        ServiceEndpointContext ctx = context();
        return ctx == null ? null : method(ctx).getOutputType().getFullName();
    }

    @Override
    public Flux<SourcedEvent> events() {
        return Flux.defer(() -> {
            ServiceEndpointContext ctx = context();
            if (ctx == null) {
                return Flux.error(new IllegalStateException(config.getGrpc().getService() + " is no longer served"));
            }
            MethodDescriptor md = method(ctx);
            DynamicMessage request = request(ctx, md);
            JsonFormat.TypeRegistry types = ctx.getTypeRegistry();
            return opener.open(
                            ctx, config.getGrpc().getService(), config.getGrpc().getMethod(), request)
                    .concatMap(message -> map(message, types));
        });
    }

    private Mono<SourcedEvent> map(DynamicMessage message, JsonFormat.TypeRegistry types) {
        Map<String, String> vars = SourcePayloads.variables(message);
        Topic topic;
        try {
            topic = template.expand(vars::get);
        } catch (IllegalArgumentException e) {
            deadLetter("topic: " + e.getMessage(), null, message, types);
            return Mono.empty();
        }
        try {
            SourcePayloads.Payload p = SourcePayloads.typed(message, types);
            Map<String, String> attributes = new LinkedHashMap<>(config.getAttributes());
            attributes.put(
                    "grpc_method",
                    config.getGrpc().getService() + "/" + config.getGrpc().getMethod());
            String key = config.getKeyField() == null || config.getKeyField().isBlank()
                    ? null
                    : vars.get(config.getKeyField());
            return Mono.just(new SourcedEvent(topic, p.json(), attributes, key, p.typeUrl(), p.proto()));
        } catch (Exception e) {
            deadLetter("payload: " + e.getMessage(), topic, message, types);
            return Mono.empty();
        }
    }

    private void deadLetter(String reason, Topic topic, DynamicMessage message, JsonFormat.TypeRegistry types) {
        byte[] data;
        String typeUrl = "type.googleapis.com/" + message.getDescriptorForType().getFullName();
        try {
            data = SourcePayloads.typed(message, types).json();
        } catch (Exception e) {
            data = message.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        dlq.add(
                name,
                reason,
                topic == null ? null : topic.toString(),
                null,
                data,
                config.getAttributes(),
                typeUrl,
                message.toByteArray());
        log.warn("Push: source {} dead-lettered a message: {}", name, reason);
    }

    private ServiceEndpointContext context() {
        String service = config.getGrpc().getService().trim();
        for (ServiceEndpointContext ctx : registry.allContexts()) {
            if (ctx.getServiceByFqn().containsKey(service)) return ctx;
        }
        return null;
    }

    private MethodDescriptor method(ServiceEndpointContext ctx) {
        String service = config.getGrpc().getService().trim();
        String method = config.getGrpc().getMethod().trim();
        MethodDescriptor md = ctx.lookupMethod(service, method)
                .orElseThrow(() -> new IllegalStateException(
                        "shiguchi.cursus.sources[" + name + "]: " + service + " has no method " + method));
        if (!md.isServerStreaming() || md.isClientStreaming()) {
            throw new IllegalStateException(
                    "shiguchi.cursus.sources[" + name + "]: " + service + "/" + method + " must be server-streaming");
        }
        return md;
    }

    private DynamicMessage request(ServiceEndpointContext ctx, MethodDescriptor md) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(md.getInputType());
        String json = config.getGrpc().getRequest();
        if (json != null && !json.isBlank()) {
            try {
                JsonFormat.Parser parser = JsonFormat.parser().ignoringUnknownFields();
                if (ctx.getTypeRegistry() != null) parser = parser.usingTypeRegistry(ctx.getTypeRegistry());
                parser.merge(json, builder);
            } catch (Exception e) {
                throw new IllegalStateException("shiguchi.cursus.sources[" + name + "]: grpc.request is not a "
                        + md.getInputType().getFullName() + ": " + e.getMessage());
            }
        }
        return builder.build();
    }
}
