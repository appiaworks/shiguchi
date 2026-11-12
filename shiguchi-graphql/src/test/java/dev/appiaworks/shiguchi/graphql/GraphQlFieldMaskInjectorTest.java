package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.FieldMask;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GraphQlFieldMaskInjector")
class GraphQlFieldMaskInjectorTest {

    private static final Descriptor DESCRIPTOR =
            FieldMask.getDescriptor().getFile().findMessageTypeByName("FieldMask");

    private static SelectedField field(String name) {
        SelectedField f = mock(SelectedField.class);
        when(f.getName()).thenReturn(name);
        return f;
    }

    private static DataFetchingEnvironment env(List<SelectedField> immediate, List<SelectedField> nodeFields) {
        DataFetchingFieldSelectionSet set = mock(DataFetchingFieldSelectionSet.class);
        when(set.getImmediateFields()).thenReturn(immediate);
        when(set.getFields("edges/node/*")).thenReturn(nodeFields);
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        when(env.getSelectionSet()).thenReturn(set);
        return env;
    }

    /** A request descriptor that has a {@code read_mask} field. */
    private static Descriptor requestWithMask() {
        Descriptor d = mock(Descriptor.class);
        when(d.findFieldByName("read_mask")).thenReturn(mock(com.google.protobuf.Descriptors.FieldDescriptor.class));
        return d;
    }

    @SuppressWarnings("unchecked")
    private static List<String> paths(Map<String, Object> request) {
        return (List<String>) ((Map<String, Object>) request.get("readMask")).get("paths");
    }

    @Test
    @DisplayName("GIVEN a connection selection WHEN the mask is built SHOULD use the node fields in snake_case")
    void given_connection_when_injected_should_maskTheNodeFields() {
        var env = env(List.of(field("edges"), field("pageInfo")), List.of(field("id"), field("publishedYear")));

        var out = new GraphQlFieldMaskInjector().inject(Map.of(), requestWithMask(), env);

        assertThat(paths(out)).containsExactly("id", "published_year");
    }

    @Test
    @DisplayName("GIVEN a plain selection WHEN the mask is built SHOULD use the immediate fields")
    void given_plainSelection_when_injected_should_useImmediateFields() {
        var env = env(List.of(field("id"), field("firstName")), List.of());

        var out = new GraphQlFieldMaskInjector().inject(Map.of(), requestWithMask(), env);

        assertThat(paths(out)).containsExactly("id", "first_name");
    }

    @Test
    @DisplayName("GIVEN a request without read_mask WHEN injected SHOULD leave the request alone")
    void given_noReadMask_when_injected_should_returnRequestUnchanged() {
        Map<String, Object> req = Map.of("a", 1);

        assertThat(new GraphQlFieldMaskInjector().inject(req, DESCRIPTOR, env(List.of(), List.of())))
                .isSameAs(req);
    }
}
