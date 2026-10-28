package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.gateway.GatewayAuthorizationEnforcer;
import dev.appiaworks.shiguchi.gateway.GrpcMetadataPopulator;
import dev.appiaworks.shiguchi.rest.HttpUpstreamDispatcher;
import dev.appiaworks.shiguchi.rest.MergeDispatcher;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import graphql.GraphQLContext;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * A server-streaming RPC bound as a GraphQL SUBSCRIPTION must reach graphql-java as a live {@link
 * Publisher} that only produces on demand and stops the backend stream when the subscriber goes
 * away; the same RPC bound as a QUERY collects into one list. This is the seam graphql-java's
 * subscription strategy and the WebSocket transport sit on, so it is pinned here at the fetcher
 * level (the starter tests prove it over a real graphql-transport-ws connection).
 */
@DisplayName("DynamicGraphQlDataFetcher server-streaming as subscription vs query")
class DynamicGraphQlDataFetcherSubscriptionTest {

    private static final FileDescriptor FILE = buildFile();

    private GrpcRouteRegistry registry;
    private ReactiveGrpcDispatcher grpcDispatcher;
    private ServiceEndpointContext ctx;
    private DynamicGraphQlDataFetcher fetcher;

    @BeforeEach
    void setUp() {
        registry = mock(GrpcRouteRegistry.class);
        grpcDispatcher = mock(ReactiveGrpcDispatcher.class);
        GatewayAuthorizationEnforcer authEnforcer = mock(GatewayAuthorizationEnforcer.class);
        when(authEnforcer.enforce(any(), any(), any())).thenReturn(Mono.empty());
        GraphQlFieldMaskInjector maskInjector = mock(GraphQlFieldMaskInjector.class);
        when(maskInjector.inject(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        ctx = new ServiceEndpointContext(new ServiceDefinition(), null);
        MethodDescriptor watch = FILE.findServiceByName("Events").findMethodByName("Watch");
        ctx.setMethodByKey(Map.of("ev.Events/Watch", watch));

        fetcher = new DynamicGraphQlDataFetcher(
                registry,
                grpcDispatcher,
                mock(RelayConnectionTransformer.class),
                maskInjector,
                mock(GraphQlSelectionProjector.class),
                mock(GrpcMetadataPopulator.class),
                authEnforcer,
                mock(MergeDispatcher.class),
                mock(HttpUpstreamDispatcher.class));
    }

    private RouteDefinition register(RouteDefinition.GraphQlOperationType type) {
        RouteDefinition r = RouteDefinition.builder(
                        "ev.Events", "Watch", io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl("itemEvents", type)
                .build();
        when(registry.findByGraphQlName("itemEvents")).thenReturn(List.of(r));
        when(registry.contextFor(r)).thenReturn(ctx);
        return r;
    }

    private static DataFetchingEnvironment env() {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getField()).thenReturn(Field.newField("itemEvents").build());
        when(env.getArguments()).thenReturn(Map.of("count", 3));
        when(env.getGraphQlContext()).thenReturn(GraphQLContext.newContext().build());
        return env;
    }

    @Test
    @DisplayName(
            "GIVEN a SUBSCRIPTION route WHEN the field is fetched SHOULD return a Publisher that emits on demand only")
    void given_subscriptionRoute_when_fetched_should_returnDemandDrivenPublisher() {
        RouteDefinition r = register(RouteDefinition.GraphQlOperationType.SUBSCRIPTION);
        when(grpcDispatcher.dispatchStreamingToMap(eq(r), eq(ctx), any(), any(), any()))
                .thenReturn(Flux.just(Map.of("id", "1"), Map.of("id", "2"), Map.of("id", "3")));

        Object result = fetcher.get(env());

        assertThat(result).isInstanceOf(Publisher.class).isNotInstanceOf(CompletableFuture.class);
        @SuppressWarnings("unchecked")
        Publisher<Object> publisher = (Publisher<Object>) result;
        StepVerifier.create(publisher, 0)
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))
                .thenRequest(1)
                .expectNext(Map.of("id", "1"))
                .expectNoEvent(Duration.ofMillis(100))
                .thenRequest(2)
                .expectNext(Map.of("id", "2"), Map.of("id", "3"))
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a SUBSCRIPTION route WHEN the subscriber cancels SHOULD cancel the backend stream")
    void given_subscriptionRoute_when_subscriberCancels_should_cancelBackendStream() {
        RouteDefinition r = register(RouteDefinition.GraphQlOperationType.SUBSCRIPTION);
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<Map<String, Object>> endless = Flux.<Map<String, Object>>generate(sink -> sink.next(Map.of("id", "x")))
                .doOnCancel(() -> cancelled.set(true));
        when(grpcDispatcher.dispatchStreamingToMap(eq(r), eq(ctx), any(), any(), any()))
                .thenReturn(endless);

        @SuppressWarnings("unchecked")
        Publisher<Object> publisher = (Publisher<Object>) fetcher.get(env());
        StepVerifier.create(publisher, 2)
                .expectNext(Map.of("id", "x"), Map.of("id", "x"))
                .thenCancel()
                .verify(Duration.ofSeconds(30));

        assertThat(cancelled)
                .as("backend stream cancelled when the subscriber left")
                .isTrue();
    }

    @Test
    @DisplayName(
            "GIVEN the same server-streaming RPC bound as a QUERY WHEN fetched SHOULD collect the stream into one list")
    void given_queryRoute_when_fetched_should_collectStreamIntoList() throws Exception {
        RouteDefinition r = register(RouteDefinition.GraphQlOperationType.QUERY);
        when(grpcDispatcher.dispatchStreamingToMap(eq(r), eq(ctx), any(), any(), any()))
                .thenReturn(Flux.just(Map.of("id", "1"), Map.of("id", "2")));

        Object result = fetcher.get(env());

        assertThat(result).isInstanceOf(CompletableFuture.class);
        assertThat(((CompletableFuture<?>) result).get()).isEqualTo(List.of(Map.of("id", "1"), Map.of("id", "2")));
    }

    /** ev.Events/Watch(WatchRequest) returns (stream Item). */
    private static FileDescriptor buildFile() {
        try {
            FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                    .setName("ev/events.proto")
                    .setSyntax("proto3")
                    .setPackage("ev")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("Item")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("id")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_STRING)))
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("WatchRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("count")
                                    .setNumber(1)
                                    .setType(FieldDescriptorProto.Type.TYPE_INT32)))
                    .addService(ServiceDescriptorProto.newBuilder()
                            .setName("Events")
                            .addMethod(MethodDescriptorProto.newBuilder()
                                    .setName("Watch")
                                    .setInputType(".ev.WatchRequest")
                                    .setOutputType(".ev.Item")
                                    .setServerStreaming(true)))
                    .build();
            return FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
