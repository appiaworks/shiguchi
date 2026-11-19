package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.GrpcLoaderConfig;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor.MethodType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Startup validation of the YAML relationships, batch keys and loaders. */
@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcBatchDataLoaderRegistrar YAML validation")
class GrpcYamlRelationshipValidationTest {

    @Mock
    private ReactiveGrpcDispatcher dispatcher;

    private GrpcRouteRegistry registry;
    private GatewayProperties properties;
    private ServiceDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        registry = new GrpcRouteRegistry();
        properties = new GatewayProperties();
        definition = new ServiceDefinition();
        definition.setName("lib");
        properties.setServices(new ArrayList<>(List.of(definition)));
        context();
    }

    private static FieldDescriptorProto field(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(Type.TYPE_INT64)
                .setLabel(Label.LABEL_OPTIONAL)
                .build();
    }

    /** Author(id), Book(id, author_id); Library/Get and Library/ByAuthors, each with a loader. */
    private void context() throws Exception {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("lib.proto")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder().setName("Empty"))
                .addMessageType(DescriptorProto.newBuilder().setName("Author").addField(field("id", 1)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Book")
                        .addField(field("id", 1))
                        .addField(field("author_id", 2)))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Library")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Get")
                                .setInputType(".Empty")
                                .setOutputType(".Book"))
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("ByAuthors")
                                .setInputType(".Empty")
                                .setOutputType(".Book")))
                .build();
        FileDescriptor fd = FileDescriptor.buildFrom(proto, new FileDescriptor[0]);
        ServiceEndpointContext ctx = new ServiceEndpointContext(definition, null);
        var service = fd.findServiceByName("Library");
        ctx.setServiceByFqn(Map.of("Library", service));
        var methods = new HashMap<String, com.google.protobuf.Descriptors.MethodDescriptor>();
        service.getMethods().forEach(m -> methods.put("Library/" + m.getName(), m));
        ctx.setMethodByKey(methods);
        ctx.setFileDescriptors(Map.of("lib.proto", fd));
        ctx.addRoute(RouteDefinition.builder("Library", "Get", MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl("books", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("Book", "ids", "")
                .build());
        ctx.addRoute(RouteDefinition.builder("Library", "ByAuthors", MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl("booksByAuthors", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor("Book", "authorIds", "")
                .loaderGroupBy("author_id")
                .build());
        registry.registerContext(ctx);
    }

    private GrpcBatchDataLoaderRegistrar registrar() {
        return new GrpcBatchDataLoaderRegistrar(
                registry, dispatcher, new DataLoaderConfig(), new SimpleMeterRegistry(), properties);
    }

    private static RelationshipConfig rel(String from, String to, boolean many, String groupBy) {
        RelationshipConfig r = new RelationshipConfig();
        r.setFrom(from);
        r.setTo(to);
        r.setMany(many);
        r.setGroupBy(groupBy);
        return r;
    }

    private void declare(RelationshipConfig... rels) {
        definition.getRelationships().addAll(List.of(rels));
    }

    @Test
    @DisplayName("GIVEN valid relationships WHEN scan runs SHOULD start cleanly")
    void GIVEN_validRelationships_WHEN_scan_SHOULD_start() {
        declare(rel("Author.id", "Book", true, "author_id"), rel("Book.author_id", "Book", false, null));
        definition.getBatchKeys().put("Book", "id");

        assertThatCode(() -> registrar().scan()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN a from type that does not exist WHEN the registrar starts SHOULD name the entry and YAML path")
    void GIVEN_unknownFromType_WHEN_start_SHOULD_fail() {
        declare(rel("Writer.id", "Book", true, "author_id"));

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shiguchi.services[0] (lib).relationships[0] (from 'Writer.id')")
                .hasMessageContaining("Writer");
    }

    @Test
    @DisplayName("GIVEN a from field that does not exist WHEN the registrar starts SHOULD fail")
    void GIVEN_unknownFromField_WHEN_start_SHOULD_fail() {
        declare(rel("Author.nope", "Book", true, "author_id"));

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relationships[0]")
                .hasMessageContaining("'nope' is not a field of Author");
    }

    @Test
    @DisplayName("GIVEN a to type without a loader WHEN the registrar starts SHOULD fail")
    void GIVEN_toTypeWithoutLoader_WHEN_start_SHOULD_fail() {
        declare(rel("Book.author_id", "Author", false, null));

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no loader for type Author");
    }

    @Test
    @DisplayName("GIVEN a grouped relationship without a loader of that group_by WHEN starting SHOULD fail")
    void GIVEN_noGroupedLoader_WHEN_start_SHOULD_fail() {
        declare(rel("Author.id", "Book", true, "id"));

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no loader for Book with group_by 'id'");
    }

    @Test
    @DisplayName("GIVEN a group-by that is not a child field WHEN the registrar starts SHOULD fail")
    void GIVEN_groupByNotChildField_WHEN_start_SHOULD_fail() {
        declare(rel("Author.id", "Book", true, "writer_id"));

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group-by 'writer_id' is not a field of Book");
    }

    @Test
    @DisplayName("GIVEN the same from declared twice WHEN the registrar starts SHOULD fail naming the duplicate")
    void GIVEN_duplicateFrom_WHEN_start_SHOULD_fail() {
        declare(rel("Author.id", "Book", true, "author_id"), rel("Author.id", "Book", true, "author_id"));

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relationships[1]")
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("GIVEN a batch key that is not a field WHEN the registrar starts SHOULD fail")
    void GIVEN_badBatchKey_WHEN_start_SHOULD_fail() {
        definition.getBatchKeys().put("Book", "isbn");

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-keys.Book")
                .hasMessageContaining("'isbn'");
    }

    @Test
    @DisplayName("GIVEN a loader whose rpc does not exist WHEN the registrar starts SHOULD fail")
    void GIVEN_unknownLoaderRpc_WHEN_start_SHOULD_fail() {
        GrpcLoaderConfig loader = new GrpcLoaderConfig();
        loader.setType("Book");
        loader.setRpc("Library/Missing");
        definition.getLoaders().add(loader);

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loaders[0] (rpc 'Library/Missing')");
    }

    @Test
    @DisplayName("GIVEN a bad entry appears after a clean start WHEN the registrar rescans SHOULD only log")
    void GIVEN_badEntryOnReload_WHEN_rescan_SHOULD_notThrow() {
        GrpcBatchDataLoaderRegistrar registrar = registrar();
        registrar.scan();
        declare(rel("Writer.id", "Book", true, "author_id"));

        assertThatCode(registrar::scan).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN a service not loaded yet WHEN scan runs SHOULD skip its YAML entries")
    void GIVEN_unloadedService_WHEN_scan_SHOULD_skip() {
        ServiceDefinition other = new ServiceDefinition();
        other.setName("later");
        other.getRelationships().add(rel("Nope.id", "Nope", false, null));
        properties.getServices().add(other);

        assertThatCode(() -> registrar().scan()).doesNotThrowAnyException();
    }
}
