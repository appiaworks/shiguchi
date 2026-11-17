package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor.MethodType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** One-to-many (group_by) loaders in the registrar: identity, startup checks. */
@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcBatchDataLoaderRegistrar grouped loaders")
class GrpcGroupedLoaderRegistrarTest {

    @Mock
    private ReactiveGrpcDispatcher dispatcher;

    private GrpcRouteRegistry registry;
    private DataLoaderConfig config;

    @BeforeEach
    void setUp() {
        registry = new GrpcRouteRegistry();
        config = new DataLoaderConfig();
    }

    private GrpcBatchDataLoaderRegistrar registrar() {
        return new GrpcBatchDataLoaderRegistrar(registry, dispatcher, config, new SimpleMeterRegistry());
    }

    /** An endpoint whose Get returns Book (id, author_id) with a by-id loader and, optionally, a grouped one. */
    private void endpoint(String name, boolean byId, String groupBy) throws Exception {
        String service = name + "Service";
        FieldOptions.Builder idOptions = FieldOptions.newBuilder()
                .setExtension(
                        GraphQlProto.field,
                        FieldRule.newBuilder().setBatchKey(true).build());
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName(name + ".proto")
                .setSyntax("proto3")
                .addDependency("shiguchi/graphql.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("Empty"))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Book")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(Type.TYPE_INT64)
                                .setLabel(Label.LABEL_OPTIONAL)
                                .setOptions(idOptions))
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("author_id")
                                .setNumber(2)
                                .setType(Type.TYPE_INT64)
                                .setLabel(Label.LABEL_OPTIONAL)))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(service)
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Get")
                                .setInputType(".Empty")
                                .setOutputType(".Book"))
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("ByAuthors")
                                .setInputType(".Empty")
                                .setOutputType(".Book")))
                .build();
        FileDescriptor fd = FileDescriptor.buildFrom(proto, new FileDescriptor[] {GraphQlProto.getDescriptor()});

        ServiceDefinition definition = new ServiceDefinition();
        definition.setName(name);
        ServiceEndpointContext ctx = new ServiceEndpointContext(definition, null);
        var serviceDescriptor = fd.findServiceByName(service);
        ctx.setServiceByFqn(Map.of(service, serviceDescriptor));
        var methods = new HashMap<String, com.google.protobuf.Descriptors.MethodDescriptor>();
        serviceDescriptor.getMethods().forEach(m -> methods.put(service + "/" + m.getName(), m));
        ctx.setMethodByKey(methods);

        if (byId) {
            ctx.addRoute(RouteDefinition.builder(service, "Get", MethodType.SERVER_STREAMING, "localhost:9090")
                    .graphQl(name + "Books", RouteDefinition.GraphQlOperationType.QUERY)
                    .loaderFor("Book", "ids", "")
                    .build());
        }
        if (groupBy != null) {
            ctx.addRoute(RouteDefinition.builder(service, "ByAuthors", MethodType.SERVER_STREAMING, "localhost:9090")
                    .graphQl(name + "BooksByAuthors", RouteDefinition.GraphQlOperationType.QUERY)
                    .loaderFor("Book", "authorIds", "")
                    .loaderGroupBy(groupBy)
                    .build());
        }
        registry.registerContext(ctx);
    }

    @Test
    @DisplayName(
            "GIVEN a by-id and a grouped loader for Book WHEN scan runs SHOULD keep both under distinct identities")
    void GIVEN_byIdAndGroupedLoader_WHEN_scan_SHOULD_registerBothWithoutCollision() throws Exception {
        endpoint("alpha", true, "author_id");
        GrpcBatchDataLoaderRegistrar registrar = registrar();

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("Book");
        assertThat(registrar.getGroupedLoaders()).containsOnlyKeys("Book#by:authorId");
        assertThat(registrar.ownerOf("Book")).contains("alpha");
        assertThat(registrar.ownerOf("Book#by:authorId")).contains("alpha");
    }

    @Test
    @DisplayName("GIVEN only a grouped loader WHEN scan runs SHOULD leave Book out of the by-id loaders")
    void GIVEN_onlyGroupedLoader_WHEN_scan_SHOULD_notExposeItAsByIdLoader() throws Exception {
        endpoint("alpha", false, "author_id");
        GrpcBatchDataLoaderRegistrar registrar = registrar();

        registrar.scan();

        assertThat(registrar.getLoaders()).isEmpty();
        assertThat(registrar.getGroupedLoaders()).containsOnlyKeys("Book#by:authorId");
    }

    @Test
    @DisplayName("GIVEN a group_by naming a missing field WHEN the registrar starts SHOULD fail with a clear message")
    void GIVEN_groupByMissingField_WHEN_start_SHOULD_fail() throws Exception {
        endpoint("alpha", false, "writer_id");

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group_by 'writer_id'")
                .hasMessageContaining("Book");
    }

    @Test
    @DisplayName("GIVEN a cache configured for a grouped loader WHEN the registrar starts SHOULD fail")
    void GIVEN_cacheForGroupedLoader_WHEN_start_SHOULD_fail() throws Exception {
        endpoint("alpha", true, "author_id");
        config.getCache().put("Book#by:authorId", new DataLoaderConfig.TypeCache());

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data-loader.cache.Book#by:authorId")
                .hasMessageContaining("group_by");
    }
}
