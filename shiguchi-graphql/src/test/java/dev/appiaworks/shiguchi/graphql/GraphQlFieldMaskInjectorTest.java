package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphQlFieldMaskInjectorTest {

    private final GraphQlFieldMaskInjector injector = new GraphQlFieldMaskInjector();

    private static FieldDescriptorProto field(String name, int n, Type type, Label label, String typeName) {
        var b = FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(n)
                .setType(type)
                .setLabel(label);
        if (typeName != null) b.setTypeName(typeName);
        return b.build();
    }

    private static final FileDescriptor FILE = build();

    private static FileDescriptor build() {
        var book = DescriptorProto.newBuilder()
                .setName("Book")
                .addField(field("id", 1, Type.TYPE_STRING, Label.LABEL_OPTIONAL, null))
                .addField(field("published_year", 2, Type.TYPE_INT32, Label.LABEL_OPTIONAL, null));
        var req = DescriptorProto.newBuilder()
                .setName("Req")
                .addField(field("read_mask", 1, Type.TYPE_STRING, Label.LABEL_OPTIONAL, null));
        var list = DescriptorProto.newBuilder()
                .setName("Resp")
                .addField(field("books", 1, Type.TYPE_MESSAGE, Label.LABEL_REPEATED, ".t.Book"));
        try {
            return FileDescriptor.buildFrom(
                    FileDescriptorProto.newBuilder()
                            .setName("t.proto")
                            .setPackage("t")
                            .setSyntax("proto3")
                            .addMessageType(book)
                            .addMessageType(req)
                            .addMessageType(list)
                            .build(),
                    new FileDescriptor[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SelectedField selected(String qualified) {
        var f = mock(SelectedField.class);
        when(f.getQualifiedName()).thenReturn(qualified);
        when(f.getName()).thenReturn(qualified.substring(qualified.lastIndexOf('/') + 1));
        return f;
    }

    private static DataFetchingEnvironment env(List<SelectedField> nodeFields) {
        var set = mock(DataFetchingFieldSelectionSet.class);
        when(set.getFields("edges/node/*")).thenReturn(nodeFields);
        var env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(set);
        return env;
    }

    @SuppressWarnings("unchecked")
    private static List<String> paths(Map<String, Object> m) {
        return (List<String>) ((Map<String, Object>) m.get("readMask")).get("paths");
    }

    @Test
    @DisplayName("GIVEN a connection response WHEN node fields are selected THEN the mask names the item's own fields")
    void GIVEN_connection_response_WHEN_node_fields_selected_SHOULD_mask_item_fields() {
        Descriptor in = FILE.findMessageTypeByName("Req");
        Descriptor out = FILE.findMessageTypeByName("Resp");
        var env = env(List.of(selected("edges/node/id"), selected("edges/node/publishedYear")));

        var result = injector.inject(Map.of(), in, out, env);

        assertThat(paths(result)).containsExactly("id", "published_year");
    }

    @Test
    @DisplayName("GIVEN only totalCount is selected WHEN injecting THEN no mask is added")
    void GIVEN_no_node_fields_WHEN_injecting_SHOULD_leave_request_unchanged() {
        var result = injector.inject(
                Map.of(), FILE.findMessageTypeByName("Req"), FILE.findMessageTypeByName("Resp"), env(List.of()));

        assertThat(result).doesNotContainKey("readMask");
    }
}
