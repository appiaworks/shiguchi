package dev.appiaworks.shiguchi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.appiaworks.shiguchi.config.ServiceDefinition;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import dev.appiaworks.shiguchi.routing.ServiceEndpointContext;
import io.grpc.MethodDescriptor;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards the document the gateway actually serves at {@code /v3/api-docs} and {@code /v3/api-docs.yaml}.
 *
 * <p>The merge tests inspect swagger model objects, which hid a bug where a 3.1 downstream schema bound
 * fine (its {@code types} set was populated) but the 3.0 serializer wrote it without any {@code type}.
 * These tests compare the <em>serialized</em> output with the downstream source: every {@code type} the
 * downstream declares must come out unchanged, for 3.0 and 3.1 documents, in JSON and YAML.
 */
@DisplayName("Served OpenAPI spec keeps downstream schema types")
class ServedSpecTypePreservationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new YAMLMapper();

    static Stream<Arguments> fixtures() {
        return Stream.of("inventory.json", "inventory.yaml", "v31-typed.json", "v31.json")
                .flatMap(f -> Stream.of(Arguments.of(f, true), Arguments.of(f, false)));
    }

    @ParameterizedTest(name = "{0}, json={1}")
    @MethodSource("fixtures")
    @DisplayName("GIVEN a downstream spec WHEN served SHOULD keep every declared type and leave no bare schema")
    void GIVEN_downstreamSpec_WHEN_served_SHOULD_keepEveryDeclaredType(String fixture, boolean asJson)
            throws Exception {
        // Arrange
        byte[] raw = load(fixture);
        boolean yamlSource = fixture.endsWith(".yaml");
        JsonNode source = (yamlSource ? YAML : JSON).readTree(raw);
        ServiceEndpointContext ctx = context("svc", raw, yamlSource ? "application/yaml" : "application/json");
        routeEveryOperation(ctx, source);
        Paths paths = new Paths();
        Components components = new Components();

        // Act
        DownstreamSpecMerger.merge(ctx, paths, components);
        OpenAPI spec = new OpenAPI().paths(paths).components(components);
        JsonNode served = asJson ? JSON.readTree(OpenApiCache.toJson(spec)) : YAML.readTree(OpenApiCache.toYaml(spec));

        // Assert
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, JsonNode> schema :
                source.path("components").path("schemas").properties()) {
            compareTypes(
                    "components/schemas/" + schema.getKey(),
                    schema.getValue(),
                    served.at("/components/schemas/svc." + schema.getKey()),
                    lost);
        }
        assertThat(lost)
                .as("types present in the downstream spec but lost or changed when served")
                .isEmpty();
        assertNoBareSchema("components", served.path("components").path("schemas"));
    }

    /** Every {@code type} (string or union array) in {@code src} must be equal at the same place in {@code out}. */
    private static void compareTypes(String where, JsonNode src, JsonNode out, List<String> lost) {
        if (src.isObject()) {
            // a property literally named "type" holds a schema object, not a type keyword
            if (src.has("type") && !src.get("type").isObject()) {
                JsonNode want = src.get("type");
                JsonNode got = out.get("type");
                if (got == null || !want.equals(got)) lost.add(where + " type: want " + want + " got " + got);
            }
            for (Map.Entry<String, JsonNode> e : src.properties()) {
                compareTypes(where + "/" + e.getKey(), e.getValue(), out.path(e.getKey()), lost);
            }
        } else if (src.isArray()) {
            for (int i = 0; i < src.size(); i++) compareTypes(where + "/" + i, src.get(i), out.path(i), lost);
        }
    }

    /** A schema that has structure must also say what it is; Swagger UI renders anything else as a bare {@code {}}. */
    private static void assertNoBareSchema(String where, JsonNode node) {
        if (node.isObject()) {
            boolean structured = node.has("properties") || node.has("items") || node.has("format");
            boolean described = node.has("type")
                    || node.has("$ref")
                    || node.has("allOf")
                    || node.has("oneOf")
                    || node.has("anyOf")
                    || node.has("enum")
                    || node.has("const");
            if (structured) {
                assertThat(described)
                        .as("schema without a type at %s: %s", where, node)
                        .isTrue();
            }
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                // inside "properties" the keys are property names, whose values are schemas
                assertNoBareSchema(where + "/" + e.getKey(), e.getValue());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) assertNoBareSchema(where + "/" + i, node.get(i));
        }
    }

    private static void routeEveryOperation(ServiceEndpointContext ctx, JsonNode source) {
        for (Map.Entry<String, JsonNode> p : source.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> op : p.getValue().properties()) {
                String method = op.getKey().toUpperCase();
                if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) continue;
                ctx.addRoute(RouteDefinition.builder(
                                "service.Service",
                                op.getValue().path("operationId").asText(op.getKey() + p.getKey()),
                                MethodDescriptor.MethodType.UNARY,
                                "localhost:50051")
                        .httpBinding(method, p.getKey())
                        .build());
            }
        }
    }

    private static ServiceEndpointContext context(String name, byte[] spec, String mediaType) {
        var config = new ServiceDefinition();
        config.setName(name);
        config.setPathPrefix("");
        ServiceEndpointContext ctx = new ServiceEndpointContext(config, null);
        ctx.setRawDownstreamSpec(spec, mediaType);
        return ctx;
    }

    private static byte[] load(String filename) throws IOException {
        try (var in = ServedSpecTypePreservationTest.class.getResourceAsStream("/openapi/" + filename)) {
            if (in == null) throw new IOException("missing test fixture /openapi/" + filename);
            return in.readAllBytes();
        }
    }
}
