package dev.appiaworks.protobuf.extras.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.FieldMask;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A request message with a {@code read_mask}, built the way the gateway builds one from {@code
 * ?fields=} (REST) and from the selection set (GraphQL): a request map holding {@code readMask:
 * {paths: [...]}}.
 *
 * <p>The REST request builder's own tests stop at that map, which is how a mask whose only path was
 * the text {@code {paths=[title]}} went unnoticed. This goes one step further, to the message the
 * backend receives.
 */
@DisplayName("A read_mask in a request map")
class FieldMaskRequestTest {

    private static final Descriptor REQUEST = requestDescriptor();

    private static Descriptor requestDescriptor() {
        try {
            FileDescriptorProto file = FileDescriptorProto.newBuilder()
                    .setName("list.proto")
                    .setPackage("t")
                    .setSyntax("proto3")
                    .addDependency("google/protobuf/field_mask.proto")
                    .addMessageType(DescriptorProto.newBuilder()
                            .setName("ListRequest")
                            .addField(FieldDescriptorProto.newBuilder()
                                    .setName("read_mask")
                                    .setJsonName("readMask")
                                    .setNumber(1)
                                    .setType(Type.TYPE_MESSAGE)
                                    .setTypeName(".google.protobuf.FieldMask")
                                    .setLabel(Label.LABEL_OPTIONAL)))
                    .build();
            return FileDescriptor.buildFrom(
                            file,
                            new FileDescriptor[] {FieldMask.getDescriptor().getFile()})
                    .findMessageTypeByName("ListRequest");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("GIVEN readMask {paths: [...]} WHEN the request message is built SHOULD hold exactly those paths")
    void given_pathsObject_when_buildRequest_should_holdThosePaths() {
        Map<String, Object> request = Map.of("readMask", Map.of("paths", List.of("title", "author.display_name")));

        DynamicMessage message = ProtoMapConverter.dynamicFromMap(request, REQUEST);

        DynamicMessage mask = (DynamicMessage) message.getField(REQUEST.findFieldByName("read_mask"));
        assertThat(mask.getField(mask.getDescriptorForType().findFieldByName("paths")))
                .asList()
                .containsExactly("title", "author.display_name");
    }
}
