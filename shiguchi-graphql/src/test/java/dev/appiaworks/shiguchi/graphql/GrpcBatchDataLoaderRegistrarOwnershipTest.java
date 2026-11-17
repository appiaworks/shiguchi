package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/** Which endpoint owns the loader of a type when several declare one. */
@ExtendWith(MockitoExtension.class)
@DisplayName("GrpcBatchDataLoaderRegistrar ownership")
class GrpcBatchDataLoaderRegistrarOwnershipTest {

    @Mock
    private ReactiveGrpcDispatcher dispatcher;

    private GrpcRouteRegistry registry;
    private DataLoaderConfig config;
    private SimpleMeterRegistry meters;
    private ListAppender<ILoggingEvent> logs;
    private Logger registrarLogger;

    @BeforeEach
    void setUp() {
        registry = new GrpcRouteRegistry();
        config = new DataLoaderConfig();
        meters = new SimpleMeterRegistry();
        registrarLogger = (Logger) LoggerFactory.getLogger(GrpcBatchDataLoaderRegistrar.class);
        logs = new ListAppender<>();
        logs.start();
        registrarLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        registrarLogger.detachAppender(logs);
    }

    private GrpcBatchDataLoaderRegistrar registrar() {
        return new GrpcBatchDataLoaderRegistrar(registry, dispatcher, config, meters);
    }

    /** Registers an endpoint with a streaming loader route for {@code type}; the route's field name is unique per endpoint. */
    private RouteDefinition endpoint(String name, String type) throws Exception {
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
                        .setName(type)
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("id")
                                .setNumber(1)
                                .setType(Type.TYPE_INT64)
                                .setLabel(Label.LABEL_OPTIONAL)
                                .setOptions(idOptions)))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName(service)
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Get")
                                .setInputType(".Empty")
                                .setOutputType("." + type)))
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

        RouteDefinition route = RouteDefinition.builder(service, "Get", MethodType.SERVER_STREAMING, "localhost:9090")
                .graphQl(name + "Items", RouteDefinition.GraphQlOperationType.QUERY)
                .loaderFor(type, "ids", "")
                .build();
        ctx.addRoute(route);
        registry.registerContext(ctx);
        return route;
    }

    @Test
    @DisplayName(
            "GIVEN two endpoints with a loader for one type WHEN the registrar starts SHOULD fail naming both and the pin key")
    void GIVEN_two_endpoints_with_a_loader_for_one_type_WHEN_start_SHOULD_fail_naming_both_and_the_pin_key()
            throws Exception {
        endpoint("alpha", "Book");
        endpoint("beta", "Book");

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Book")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta")
                .hasMessageContaining("shiguchi.graphql.data-loader.types.Book.endpoint");
    }

    @Test
    @DisplayName(
            "GIVEN a pin to one of two endpoints WHEN scan runs SHOULD register only that loader under the plain name")
    void GIVEN_a_pin_to_one_of_two_endpoints_WHEN_scan_SHOULD_register_only_that_loader_under_the_plain_name()
            throws Exception {
        endpoint("alpha", "Book");
        endpoint("beta", "Book");
        pin("Book", "beta");
        GrpcBatchDataLoaderRegistrar registrar = registrar();

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("Book");
        assertThat(registrar.ownerOf("Book")).contains("beta");
        assertThat(logs.list)
                .anyMatch(e ->
                        e.getLevel() == Level.INFO && e.getFormattedMessage().contains("alpha"));
    }

    @Test
    @DisplayName("GIVEN a pin to an endpoint without a loader for the type WHEN the registrar starts SHOULD fail")
    void GIVEN_a_pin_to_an_endpoint_without_a_loader_WHEN_start_SHOULD_fail() throws Exception {
        endpoint("alpha", "Book");
        endpoint("beta", "Author");
        pin("Book", "beta");

        assertThatThrownBy(() -> registrar().scan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("types.Book.endpoint")
                .hasMessageContaining("beta");
    }

    @Test
    @DisplayName(
            "GIVEN one endpoint per type WHEN scan runs SHOULD keep the plain loader names and agree with the route's endpoint")
    void GIVEN_one_endpoint_per_type_WHEN_scan_SHOULD_keep_plain_names_and_agree_with_the_routes_endpoint()
            throws Exception {
        RouteDefinition book = endpoint("alpha", "Book");
        RouteDefinition author = endpoint("beta", "Author");
        GrpcBatchDataLoaderRegistrar registrar = registrar();

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("Book", "Author");
        assertThat(registrar.ownerOf("Book"))
                .contains(registry.contextFor(book).getConfig().getName());
        assertThat(registrar.ownerOf("Author"))
                .contains(registry.contextFor(author).getConfig().getName());
    }

    @Test
    @DisplayName(
            "GIVEN a reload that adds a conflicting loader WHEN scan runs SHOULD keep the owner, log ERROR and count it")
    void GIVEN_a_reload_that_adds_a_conflicting_loader_WHEN_scan_SHOULD_keep_owner_log_error_and_count()
            throws Exception {
        endpoint("alpha", "Book");
        GrpcBatchDataLoaderRegistrar registrar = registrar();
        registrar.scan();
        BatchLoader owner = registrar.getLoaders().get("Book");

        endpoint("beta", "Book");
        registrar.scan();
        registrar.scan();

        assertThat(registrar.getLoaders().get("Book")).isSameAs(owner);
        assertThat(registrar.ownerOf("Book")).contains("alpha");
        assertThat(logs.list)
                .filteredOn(e -> e.getLevel() == Level.ERROR)
                .hasSize(1)
                .allMatch(e -> e.getFormattedMessage().contains("alpha")
                        && e.getFormattedMessage().contains("beta")
                        && e.getFormattedMessage().contains("types.Book.endpoint"));
        assertThat(meters.get("shiguchi.graphql.loader.conflicts").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("GIVEN a service replaced under the same name WHEN scan runs SHOULD see no conflict")
    void GIVEN_a_service_replaced_under_the_same_name_WHEN_scan_SHOULD_see_no_conflict() throws Exception {
        endpoint("alpha", "Book");
        GrpcBatchDataLoaderRegistrar registrar = registrar();
        registrar.scan();

        // the refresh swaps alpha's context for a new one carrying the same loader
        endpoint("alpha", "Book");
        registrar.scan();

        assertThat(registrar.ownerOf("Book")).contains("alpha");
        assertThat(logs.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(meters.find("shiguchi.graphql.loader.conflicts").counter()).isNull();
    }

    private void pin(String type, String endpoint) {
        DataLoaderConfig.TypeConfig typeConfig = new DataLoaderConfig.TypeConfig();
        typeConfig.setEndpoint(endpoint);
        config.getTypes().put(type, typeConfig);
    }
}
