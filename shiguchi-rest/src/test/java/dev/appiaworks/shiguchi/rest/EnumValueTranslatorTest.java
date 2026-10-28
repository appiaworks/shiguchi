package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import dev.appiaworks.shiguchi.routing.DownstreamOpenApiDocument;
import dev.appiaworks.shiguchi.routing.OpenApiToDescriptor;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enum values cross the HTTP boundary in the backend's own spelling and live inside the gateway
 * as proto value names. The mapping comes from the descriptor builder, keyed by the synthetic
 * enum's full name.
 */
@DisplayName("EnumValueTranslator between backend strings and proto value names")
class EnumValueTranslatorTest {

    private static Descriptor item;
    private static Map<String, Map<String, String>> originals;

    @BeforeAll
    static void deriveDescriptor() throws Exception {
        byte[] doc;
        try (InputStream in = EnumValueTranslatorTest.class.getResourceAsStream("/openapi/enum-values.json")) {
            doc = in.readAllBytes();
        }
        OpenApiToDescriptor.Result result =
                OpenApiToDescriptor.build("inventory", "", DownstreamOpenApiDocument.parse(doc, "application/json"));
        item = result.file().findMessageTypeByName("Item");
        originals = result.enumValueOriginals();
    }

    @Test
    @DisplayName("Given the builder ran on a document with enums, when the originals are read, "
            + "should map each proto value name to the document's own string, per enum full name")
    void given_documentWithEnums_when_built_should_recordOriginals() {
        assertThat(originals).containsKey("rest.inventory.ItemStatus");
        assertThat(originals.get("rest.inventory.ItemStatus"))
                .containsEntry("ITEM_STATUS_ACTIVE", "active")
                .containsEntry("ITEM_STATUS_ON_HOLD", "on-hold");
    }

    @Test
    @DisplayName("Given a backend response with lower-case enum strings, when translated to proto, "
            + "should replace them with the proto value names, including nested and repeated fields")
    void given_backendValues_when_toProto_should_useProtoNames() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("sku", "1");
        raw.put("status", "on-hold");
        raw.put("history", List.of("active", "on-hold"));
        raw.put("category", Map.of("kind", "tools"));

        Map<String, Object> out = EnumValueTranslator.toProto(raw, item, originals);

        assertThat(out).containsEntry("sku", "1").containsEntry("status", "ITEM_STATUS_ON_HOLD");
        assertThat(out.get("history")).isEqualTo(List.of("ITEM_STATUS_ACTIVE", "ITEM_STATUS_ON_HOLD"));
        assertThat(out.get("category")).isEqualTo(Map.of("kind", "CATEGORY_KIND_TOOLS"));
    }

    @Test
    @DisplayName("Given arguments carrying proto value names, when translated to the backend, "
            + "should send the document's original strings")
    void given_protoNames_when_toBackend_should_useOriginals() {
        Map<String, Object> args = Map.of("status", "ITEM_STATUS_ACTIVE", "history", List.of("ITEM_STATUS_ON_HOLD"));

        Map<String, Object> out = EnumValueTranslator.toBackend(args, item, originals);

        assertThat(out).containsEntry("status", "active");
        assertThat(out.get("history")).isEqualTo(List.of("on-hold"));
    }

    @Test
    @DisplayName("Given a value the document never declared, when translated either way, "
            + "should pass it through unchanged and leave the input Map untouched")
    void given_unknownValue_when_translated_should_passThrough() {
        Map<String, Object> raw = Map.of("status", "archived", "sku", "2");

        Map<String, Object> toProto = EnumValueTranslator.toProto(raw, item, originals);
        Map<String, Object> toBackend =
                EnumValueTranslator.toBackend(Map.of("status", "ITEM_STATUS_ACTIVE"), item, originals);

        assertThat(toProto).isSameAs(raw);
        assertThat(toBackend).containsEntry("status", "active");
    }

    @Test
    @DisplayName("Given no recorded originals (a gRPC context), when translated, should return the same Map")
    void given_noOriginals_when_translated_should_returnSameInstance() {
        Map<String, Object> raw = Map.of("status", "active");

        assertThat(EnumValueTranslator.toProto(raw, item, Map.of())).isSameAs(raw);
        assertThat(EnumValueTranslator.toBackend(raw, item, null)).isSameAs(raw);
    }
}
