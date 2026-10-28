package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.appiaworks.protobuf.extras.serde.ProjectionNode;
import dev.appiaworks.shiguchi.routing.RouteDefinition;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingFieldSelectionSet;
import graphql.schema.SelectedField;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the selection → proto projection mapping, including the Relay connection
 * remap (edges/node → items field, pageInfo always whole) and response-pluck wrapping.
 */
class GraphQlSelectionProjectorTest {

    private static Descriptor plainResponse;
    private static Descriptor pagedResponse;

    private final RelayConnectionTransformer relay = new RelayConnectionTransformer();
    private final GraphQlSelectionProjector projector = new GraphQlSelectionProjector(relay, true);

    @BeforeAll
    static void buildDescriptors() throws Exception {
        DescriptorProto user = DescriptorProto.newBuilder()
                .setName("User")
                .addField(scalar("name", 1))
                .build();
        DescriptorProto pageInfo = DescriptorProto.newBuilder()
                .setName("PageInfo")
                .addField(scalar("next_page_token", 1))
                .build();
        DescriptorProto plain = DescriptorProto.newBuilder()
                .setName("GetUserResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("user")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.User")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();
        DescriptorProto paged = DescriptorProto.newBuilder()
                .setName("ListUsersResponse")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("user_items")
                        .setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.User")
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED))
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("page_info")
                        .setNumber(2)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".t.PageInfo")
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL))
                .build();

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("projector-test.proto")
                        .setSyntax("proto3")
                        .setPackage("t")
                        .addMessageType(user)
                        .addMessageType(pageInfo)
                        .addMessageType(plain)
                        .addMessageType(paged)
                        .build(),
                new FileDescriptor[0]);
        plainResponse = file.findMessageTypeByName("GetUserResponse");
        pagedResponse = file.findMessageTypeByName("ListUsersResponse");
    }

    private static DataFetchingEnvironment envWithSelection(String... qualifiedNames) {
        DataFetchingEnvironment env = mock(DataFetchingEnvironment.class);
        DataFetchingFieldSelectionSet selectionSet = mock(DataFetchingFieldSelectionSet.class);
        List<SelectedField> fields = java.util.Arrays.stream(qualifiedNames)
                .map(name -> {
                    SelectedField f = mock(SelectedField.class);
                    when(f.getQualifiedName()).thenReturn(name);
                    return f;
                })
                .toList();
        when(selectionSet.getFields()).thenReturn(fields);
        when(env.getSelectionSet()).thenReturn(selectionSet);
        return env;
    }

    private static RouteDefinition routeWithPluck(String pluck) {
        RouteDefinition route = mock(RouteDefinition.class);
        when(route.getGraphQlResponsePluck()).thenReturn(pluck);
        return route;
    }

    @Test
    @DisplayName("Given a plain selection, when build, should mirror the selection tree")
    void given_plainSelection_when_build_should_mirrorSelection() {
        ProjectionNode projection =
                projector.build(envWithSelection("user", "user/name"), routeWithPluck(null), plainResponse);

        assertThat(projection).isNotNull();
        assertThat(projection.lookup("user")).isNotNull();
        assertThat(projection.lookup("user").lookup("name")).isNotNull();
        assertThat(projection.lookup("other")).isNull();
    }

    @Test
    @DisplayName(
            "Given a connection response, when build, should graft edges/node onto the items field and keep pageInfo whole")
    void given_connectionSelection_when_build_should_remapEdgesAndKeepPageInfo() {
        ProjectionNode projection = projector.build(
                envWithSelection(
                        "edges", "edges/node", "edges/node/name", "edges/cursor", "pageInfo", "pageInfo/endCursor"),
                routeWithPluck(null),
                pagedResponse);

        assertThat(projection).isNotNull();
        ProjectionNode items = projection.lookup("userItems");
        assertThat(items).isNotNull();
        assertThat(items.lookup("name")).isNotNull();
        assertThat(projection.lookup("pageInfo").isIncludeAll()).isTrue();
    }

    @Test
    @DisplayName(
            "Given a connection selection without pageInfo, when build, should still include pageInfo for the relay transform")
    void given_connectionWithoutPageInfoSelected_when_build_should_stillIncludePageInfo() {
        ProjectionNode projection = projector.build(
                envWithSelection("edges", "edges/node", "edges/node/name"), routeWithPluck(null), pagedResponse);

        assertThat(projection.lookup("pageInfo")).isNotNull();
        assertThat(projection.lookup("pageInfo").isIncludeAll()).isTrue();
    }

    @Test
    @DisplayName("Given a response pluck, when build, should wrap the selection under the plucked field")
    void given_responsePluck_when_build_should_wrapUnderPluckedField() {
        ProjectionNode projection = projector.build(envWithSelection("name"), routeWithPluck("user"), plainResponse);

        assertThat(projection).isNotNull();
        assertThat(projection.lookup("user")).isNotNull();
        assertThat(projection.lookup("user").lookup("name")).isNotNull();
    }

    @Test
    @DisplayName("Given projection disabled, when build, should return null so the full tree converts")
    void given_projectionDisabled_when_build_should_returnNull() {
        GraphQlSelectionProjector disabled = new GraphQlSelectionProjector(relay, false);

        assertThat(disabled.build(envWithSelection("user"), routeWithPluck(null), plainResponse))
                .isNull();
    }

    private static FieldDescriptorProto scalar(String name, int number) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_STRING)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }
}
