package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for selection-aware projection: scalar fields must always convert (type
 * resolvers and federation batch keys may read them), while composite subtrees convert
 * only when selected.
 */
class ProtoMapProjectionTest {

    private static Descriptor order;
    private static Descriptor item;
    private static DynamicMessage sample;

    @BeforeAll
    static void buildFixture() throws Exception {
        DescriptorProto itemMsg = DescriptorProto.newBuilder()
                .setName("Item")
                .addField(scalar("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("qty", 2, FieldDescriptorProto.Type.TYPE_INT32))
                .build();
        DescriptorProto customerMsg = DescriptorProto.newBuilder()
                .setName("Customer")
                .addField(scalar("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(message("home", 2, ".p.Address"))
                .build();
        DescriptorProto addressMsg = DescriptorProto.newBuilder()
                .setName("Address")
                .addField(scalar("city", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        DescriptorProto orderMsg = DescriptorProto.newBuilder()
                .setName("Order")
                .addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_INT64))
                .addField(scalar("note", 2, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(repeated(message("items", 3, ".p.Item")))
                .addField(message("customer", 4, ".p.Customer"))
                .addField(repeated(scalar("tags", 5, FieldDescriptorProto.Type.TYPE_STRING)))
                .addNestedType(DescriptorProto.newBuilder()
                        .setName("LabelsEntry")
                        .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                        .addField(scalar("key", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(scalar("value", 2, FieldDescriptorProto.Type.TYPE_STRING)))
                .addField(repeated(message("labels", 6, ".p.Order.LabelsEntry")))
                .addField(message("created_at", 7, ".google.protobuf.Timestamp"))
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("proj.proto")
                        .setSyntax("proto3")
                        .setPackage("p")
                        .addDependency("google/protobuf/timestamp.proto")
                        .addMessageType(itemMsg)
                        .addMessageType(customerMsg)
                        .addMessageType(addressMsg)
                        .addMessageType(orderMsg)
                        .build(),
                new FileDescriptor[] {Timestamp.getDescriptor().getFile()});
        order = file.findMessageTypeByName("Order");
        item = file.findMessageTypeByName("Item");

        Descriptor customer = file.findMessageTypeByName("Customer");
        Descriptor address = file.findMessageTypeByName("Address");

        DynamicMessage home = DynamicMessage.newBuilder(address)
                .setField(address.findFieldByName("city"), "Springfield")
                .build();
        DynamicMessage cust = DynamicMessage.newBuilder(customer)
                .setField(customer.findFieldByName("name"), "Ada")
                .setField(customer.findFieldByName("home"), home)
                .build();
        Descriptor ts = order.findFieldByName("created_at").getMessageType();
        DynamicMessage.Builder b = DynamicMessage.newBuilder(order)
                .setField(order.findFieldByName("id"), 42L)
                .setField(order.findFieldByName("note"), "gift")
                .setField(order.findFieldByName("customer"), cust)
                .setField(
                        order.findFieldByName("created_at"),
                        DynamicMessage.newBuilder(ts)
                                .setField(ts.findFieldByNumber(1), 1_700_000_000L)
                                .build());
        b.addRepeatedField(order.findFieldByName("tags"), "a");
        FieldDescriptor itemsFd = order.findFieldByName("items");
        for (int i = 0; i < 3; i++) {
            b.addRepeatedField(
                    itemsFd,
                    DynamicMessage.newBuilder(item)
                            .setField(item.findFieldByName("sku"), "sku-" + i)
                            .setField(item.findFieldByName("qty"), i)
                            .build());
        }
        FieldDescriptor labelsFd = order.findFieldByName("labels");
        Descriptor entry = labelsFd.getMessageType();
        b.addRepeatedField(
                labelsFd,
                DynamicMessage.newBuilder(entry)
                        .setField(entry.findFieldByNumber(1), "env")
                        .setField(entry.findFieldByNumber(2), "prod")
                        .build());
        sample = b.build();
    }

    @Test
    @DisplayName("Given no projection, when projectToMap, should equal the full dynamicToMap conversion")
    void given_nullProjection_when_projectToMap_should_equalFullConversion() {
        assertThat(ProtoMapConverter.projectToMap(sample, null)).isEqualTo(ProtoMapConverter.dynamicToMap(sample));
    }

    @Test
    @DisplayName(
            "Given a projection selecting nothing composite, when projectToMap, should keep scalars and drop all composites")
    void given_emptySelection_when_projectToMap_should_keepScalarsDropComposites() {
        Map<String, Object> result = ProtoMapConverter.projectToMap(sample, ProjectionNode.root());

        assertThat(result)
                .containsEntry("id", 42L)
                .containsEntry("note", "gift")
                .containsKey("tags") // repeated scalars always convert
                .containsKey("createdAt") // WKT scalar always converts
                .doesNotContainKeys("items", "customer", "labels");
    }

    @Test
    @DisplayName("Given a projection selecting items.sku, when projectToMap, should project list elements recursively")
    void given_nestedListSelection_when_projectToMap_should_projectElements() {
        ProjectionNode projection = ProjectionNode.root();
        projection.addPath("items/sku");

        Map<String, Object> result = ProtoMapConverter.projectToMap(sample, projection);

        assertThat(result).containsKey("items").doesNotContainKeys("customer", "labels");
        @SuppressWarnings("unchecked")
        var items = (java.util.List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(3);
        // sku selected; qty is a scalar so it converts too (scalar-always rule)
        assertThat(items.get(0)).containsEntry("sku", "sku-0").containsEntry("qty", 0);
    }

    @Test
    @DisplayName(
            "Given a projection selecting customer.name, when projectToMap, should drop the unselected nested address")
    void given_partialNestedSelection_when_projectToMap_should_dropUnselectedSubtree() {
        ProjectionNode projection = ProjectionNode.root();
        projection.addPath("customer/name");

        Map<String, Object> result = ProtoMapConverter.projectToMap(sample, projection);

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) result.get("customer");
        assertThat(customer).containsEntry("name", "Ada").doesNotContainKey("home");
    }

    @Test
    @DisplayName("Given a map field selected, when projectToMap, should include the map as a whole")
    void given_mapFieldSelected_when_projectToMap_should_includeMap() {
        ProjectionNode projection = ProjectionNode.root();
        projection.addPath("labels");

        Map<String, Object> result = ProtoMapConverter.projectToMap(sample, projection);

        assertThat(result.get("labels")).isEqualTo(Map.of("env", "prod"));
    }

    @Test
    @DisplayName("Given an includeAll subtree, when projectToMap, should convert that subtree in full")
    void given_includeAllSubtree_when_projectToMap_should_convertSubtreeFully() {
        ProjectionNode projection = ProjectionNode.root();
        projection.setChild("customer", ProjectionNode.includeAll());

        Map<String, Object> result = ProtoMapConverter.projectToMap(sample, projection);

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) result.get("customer");
        assertThat(customer).containsKey("home");
    }

    private static FieldDescriptorProto scalar(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto message(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto repeated(FieldDescriptorProto field) {
        return field.toBuilder()
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .build();
    }
}
