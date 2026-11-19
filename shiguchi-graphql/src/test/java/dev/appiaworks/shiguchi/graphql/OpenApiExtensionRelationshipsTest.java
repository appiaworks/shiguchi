package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import dev.appiaworks.shiguchi.config.DataLoaderConfig;
import dev.appiaworks.shiguchi.config.GatewayProperties;
import dev.appiaworks.shiguchi.config.RelationshipConfig;
import dev.appiaworks.shiguchi.config.RestBatchLoaderConfig;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.graphql.GraphQlSchemaBuilder.Relationship;
import dev.appiaworks.shiguchi.rest.ReactiveGrpcDispatcher;
import dev.appiaworks.shiguchi.routing.DownstreamOpenApiDocument;
import dev.appiaworks.shiguchi.routing.GrpcRouteRegistry;
import dev.appiaworks.shiguchi.routing.OpenApiToDescriptor;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The x-shiguchi-* OpenAPI extensions as a relationship source, and their validation. */
@DisplayName("OpenAPI x-shiguchi extensions as relationships")
class OpenApiExtensionRelationshipsTest {

    private static final String SCHEMAS = "\"components\":{\"schemas\":{"
            + "\"Customer\":{\"type\":\"object\",\"x-shiguchi-batch-key\":\"id\",\"properties\":{\"id\":{\"type\":\"string\"}}},"
            + "\"Order\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"},"
            + "\"customerId\":{\"type\":\"string\",\"x-shiguchi-references\":\"Customer\"}}}}}";

    private static final String LOADER_PATH =
            "\"/customers\":{\"get\":{\"operationId\":\"listCustomers\",\"x-shiguchi-loader\":{\"type\":\"Customer\",\"ids-param\":\"ids\"},"
                    + "\"responses\":{\"200\":{\"description\":\"OK\"}}}}";

    private static String doc(String paths) {
        return "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"S\",\"version\":\"1\"},\"paths\":{" + paths + "},"
                + SCHEMAS + "}";
    }

    private final GrpcRouteRegistry registry = new GrpcRouteRegistry();
    private final GatewayProperties properties = new GatewayProperties();
    private final ServiceDefinition definition = new ServiceDefinition();
    private ServiceEndpointContext ctx;

    private void load(String json) throws Exception {
        definition.setName("shop");
        definition.setMode(dev.appiaworks.shiguchi.config.GatewayMode.REST_DOWNSTREAM);
        definition.setOpenapiUrl("http://shop/v3/api-docs");
        definition.setGraphqlTypePrefix("Shop");
        properties.setServices(new ArrayList<>(List.of(definition)));
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        var file = OpenApiToDescriptor.build("shop", "Shop", DownstreamOpenApiDocument.parse(raw, "application/json"))
                .file();
        ctx = new ServiceEndpointContext(definition, null);
        ctx.setRawDownstreamSpec(raw, "application/json");
        ctx.setFileDescriptors(Map.of(file.getName(), file));
        registry.registerContext(ctx);
    }

    private Descriptor message(String name) {
        return ctx.getFileDescriptors().values().iterator().next().getMessageTypes().stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private OpenApiRelationshipSource source() {
        return new OpenApiRelationshipSource(new OpenApiRelationshipIndex(registry));
    }

    private List<Relationship> resolve(RelationshipResolver resolver, String type, String property) {
        Descriptor parent = message(type);
        FieldDescriptor field = parent.findFieldByName(OpenApiToDescriptor.protoFieldName(property));
        return resolver.relationships(parent, field);
    }

    private GrpcBatchDataLoaderRegistrar grpcRegistrar() {
        return new GrpcBatchDataLoaderRegistrar(
                registry,
                Mockito.mock(ReactiveGrpcDispatcher.class),
                new DataLoaderConfig(),
                new SimpleMeterRegistry(),
                properties);
    }

    private static RelationshipConfig yaml(String from, String to) {
        RelationshipConfig r = new RelationshipConfig();
        r.setFrom(from);
        r.setTo(to);
        r.setField("customer");
        return r;
    }

    @Test
    @DisplayName("GIVEN an x-shiguchi-references property WHEN resolved SHOULD yield an EXTENSION relationship")
    void GIVEN_references_WHEN_resolved_SHOULD_yieldExtensionRelationship() throws Exception {
        load(doc(LOADER_PATH));

        var rels = resolve(RelationshipResolver.of(properties, List.of(source())), "ShopOrder", "customerId");

        assertThat(rels).singleElement().satisfies(r -> {
            assertThat(r.resolvedType()).isEqualTo("ShopCustomer");
            assertThat(r.source()).isEqualTo(RelationshipSource.Source.EXTENSION);
        });
    }

    @Test
    @DisplayName("GIVEN an x-shiguchi-batch-key WHEN the batch key is asked SHOULD return it as EXTENSION")
    void GIVEN_batchKey_WHEN_asked_SHOULD_returnExtensionKey() throws Exception {
        load(doc(LOADER_PATH));

        var key = RelationshipResolver.of(properties, List.of(source())).batchKey(message("ShopCustomer"));

        assertThat(key).isPresent();
        assertThat(key.get().protoField()).isEqualTo("id");
        assertThat(key.get().source()).isEqualTo(RelationshipSource.Source.EXTENSION);
    }

    @Test
    @DisplayName("GIVEN YAML and an extension on the same field WHEN resolved SHOULD let the YAML win")
    void GIVEN_yamlAndExtension_WHEN_resolved_SHOULD_yamlWins() throws Exception {
        load(doc(LOADER_PATH));
        definition.getRelationships().add(yaml("ShopOrder.customerId", "ShopCustomer"));

        var rels = resolve(RelationshipResolver.of(properties, List.of(source())), "ShopOrder", "customerId");

        assertThat(rels).singleElement().satisfies(r -> {
            assertThat(r.source()).isEqualTo(RelationshipSource.Source.YAML);
            assertThat(r.fieldName()).isEqualTo("customer");
        });
    }

    @Test
    @DisplayName("GIVEN an annotation-tier and an extension on the same key WHEN resolved SHOULD let the extension win")
    void GIVEN_annotationAndExtension_WHEN_resolved_SHOULD_extensionWins() throws Exception {
        load(doc(LOADER_PATH));
        RelationshipSource annotation = new RelationshipSource() {
            @Override
            public Source source() {
                return Source.ANNOTATION;
            }

            @Override
            public List<Relationship> relationships(Descriptor parent, FieldDescriptor field) {
                return List.of(new Relationship(
                        parent.getName(), "customer", field.getJsonName(), "Other", "", Source.ANNOTATION));
            }
        };

        var rels =
                resolve(RelationshipResolver.of(properties, List.of(source(), annotation)), "ShopOrder", "customerId");

        assertThat(rels).singleElement().satisfies(r -> {
            assertThat(r.source()).isEqualTo(RelationshipSource.Source.EXTENSION);
            assertThat(r.resolvedType()).isEqualTo("ShopCustomer");
        });
    }

    @Test
    @DisplayName("GIVEN the suggester has run WHEN resolving SHOULD return the same relationships as before")
    void GIVEN_suggesterRun_WHEN_resolved_SHOULD_leaveResolverOutputUnchanged() throws Exception {
        load(doc("").replace("\"x-shiguchi-references\":\"Customer\"", "\"x-other\":true"));
        var resolver = RelationshipResolver.of(properties, List.of(source()));
        var before = resolve(resolver, "ShopOrder", "customerId");

        var suggestions = RelationshipSuggester.suggest(
                DownstreamOpenApiDocument.parse(
                        doc("").replace("\"x-shiguchi-references\":\"Customer\"", "\"x-other\":true")
                                .getBytes(StandardCharsets.UTF_8),
                        "application/json"),
                "Shop");

        assertThat(suggestions).isNotEmpty();
        assertThat(before).isEmpty();
        assertThat(resolve(resolver, "ShopOrder", "customerId")).isEqualTo(before);
    }

    @Test
    @DisplayName(
            "GIVEN a YAML loader and an extension loader with the same name WHEN scanned SHOULD keep one, the YAML")
    void GIVEN_sameLoaderInYamlAndExtension_WHEN_scan_SHOULD_registerOneYamlLoader() throws Exception {
        load(doc(LOADER_PATH));
        RestBatchLoaderConfig yamlLoader = new RestBatchLoaderConfig();
        yamlLoader.setType("ShopCustomer");
        yamlLoader.setEndpoint("/yaml-customers");
        definition.setRestBatchLoaders(List.of(yamlLoader));
        var provider = new StaticListableBeanFactory(Map.of("i", new OpenApiRelationshipIndex(registry)))
                .getBeanProvider(OpenApiRelationshipIndex.class);
        var registrar = new RestBatchDataLoaderRegistrar(properties, provider);

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("ShopCustomer");
        assertThat(new OpenApiRelationshipIndex(registry).loaders("shop")).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN an extension loader and no YAML WHEN scanned SHOULD register it")
    void GIVEN_extensionLoaderOnly_WHEN_scan_SHOULD_registerIt() throws Exception {
        load(doc(LOADER_PATH));
        var provider = new StaticListableBeanFactory(Map.of("i", new OpenApiRelationshipIndex(registry)))
                .getBeanProvider(OpenApiRelationshipIndex.class);
        var registrar = new RestBatchDataLoaderRegistrar(properties, provider);

        registrar.scan();

        assertThat(registrar.getLoaders()).containsOnlyKeys("ShopCustomer");
    }

    @Test
    @DisplayName(
            "GIVEN an extension to a type with no loader WHEN the registrar first starts SHOULD fail naming the extension")
    void GIVEN_extensionWithoutLoader_WHEN_firstScan_SHOULD_fail() throws Exception {
        load(doc(""));
        var registrar = grpcRegistrar();
        registrar.setOpenApiRelationshipIndex(new OpenApiRelationshipIndex(registry));

        assertThatThrownBy(registrar::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAPI x-shiguchi-references on Order.customerId")
                .hasMessageContaining("no loader");
    }

    @Test
    @DisplayName("GIVEN an extension to a type with no loader WHEN the registrar rescans SHOULD only log")
    void GIVEN_extensionWithoutLoader_WHEN_rescan_SHOULD_notThrow() throws Exception {
        load(doc(LOADER_PATH));
        var registrar = grpcRegistrar();
        registrar.setOpenApiRelationshipIndex(new OpenApiRelationshipIndex(registry));
        registrar.scan();

        load(doc(""));
        assertThatCode(registrar::scan).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GIVEN an extension naming an unknown schema WHEN the registrar first starts SHOULD fail")
    void GIVEN_unknownSchemaExtension_WHEN_firstScan_SHOULD_fail() throws Exception {
        load(doc(LOADER_PATH)
                .replace("\"x-shiguchi-references\":\"Customer\"", "\"x-shiguchi-references\":\"Nobody\""));
        var registrar = grpcRegistrar();
        registrar.setOpenApiRelationshipIndex(new OpenApiRelationshipIndex(registry));

        assertThatThrownBy(registrar::scan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAPI x-shiguchi-references on Order.customerId")
                .hasMessageContaining("Nobody");
    }
}
