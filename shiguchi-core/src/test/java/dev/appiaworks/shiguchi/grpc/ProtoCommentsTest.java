package dev.appiaworks.shiguchi.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading proto comments out of a descriptor's {@code SourceCodeInfo}.
 *
 * <p>The locations are built by hand here because that is what {@code protoc
 * --include_source_info} emits: a flat list of paths addressing elements by field number and
 * index. The test is really about whether those paths are decoded into the right names.
 */
@DisplayName("Proto comments")
class ProtoCommentsTest {

    private static SourceCodeInfo.Location at(String leading, Integer... path) {
        return SourceCodeInfo.Location.newBuilder()
                .addAllPath(List.of(path))
                .setLeadingComments(leading)
                .build();
    }

    /** A file with one service, one method, one message and one field — all commented. */
    private static FileDescriptorProto commented() {
        return FileDescriptorProto.newBuilder()
                .setName("shop/catalog.proto")
                .setPackage("shop.v1")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Item")
                        .addField(FieldDescriptorProto.newBuilder()
                                .setName("sku")
                                .setNumber(1)
                                .setType(FieldDescriptorProto.Type.TYPE_STRING)))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("CatalogService")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("GetItem")
                                .setInputType(".shop.v1.Item")
                                .setOutputType(".shop.v1.Item")))
                .setSourceCodeInfo(SourceCodeInfo.newBuilder()
                        .addLocation(at(" The product catalog.\n", 6, 0))
                        .addLocation(at(" Fetches one item by SKU.\n", 6, 0, 2, 0))
                        .addLocation(at(" One product.\n", 4, 0))
                        .addLocation(at(" Stock keeping unit.\n", 4, 0, 2, 0)))
                .build();
    }

    @Test
    @DisplayName("GIVEN a commented proto WHEN read SHOULD key each comment by the fully-qualified name it documents")
    void given_commentedProto_when_read_should_keyByFullName() {
        Map<String, String> comments = ProtoComments.of(commented());

        assertThat(comments)
                .containsEntry("shop.v1.CatalogService", "The product catalog.")
                .containsEntry("shop.v1.CatalogService.GetItem", "Fetches one item by SKU.")
                .containsEntry("shop.v1.Item", "One product.")
                .containsEntry("shop.v1.Item.sku", "Stock keeping unit.");
    }

    @Test
    @DisplayName(
            "GIVEN a descriptor with no source info WHEN read SHOULD return nothing, since reflection and stubs both drop it")
    void given_noSourceInfo_when_read_should_returnEmpty() {
        FileDescriptorProto bare = commented().toBuilder().clearSourceCodeInfo().build();

        assertThat(ProtoComments.of(bare)).isEmpty();
        assertThat(ProtoComments.of(null)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a multi-line comment WHEN read SHOULD keep the lines and drop protoc's leading space on each")
    void given_multiLineComment_when_read_should_tidyEachLine() {
        FileDescriptorProto file = commented().toBuilder()
                .setSourceCodeInfo(SourceCodeInfo.newBuilder().addLocation(at(" First line.\n Second line.\n", 6, 0)))
                .build();

        assertThat(ProtoComments.of(file)).containsEntry("shop.v1.CatalogService", "First line.\nSecond line.");
    }

    @Test
    @DisplayName("GIVEN only a trailing comment WHEN read SHOULD use it, since a note after a field still documents it")
    void given_trailingComment_when_read_should_useIt() {
        FileDescriptorProto file = commented().toBuilder()
                .setSourceCodeInfo(SourceCodeInfo.newBuilder()
                        .addLocation(SourceCodeInfo.Location.newBuilder()
                                .addAllPath(List.of(4, 0, 2, 0))
                                .setTrailingComments(" the SKU\n")))
                .build();

        assertThat(ProtoComments.of(file)).containsEntry("shop.v1.Item.sku", "the SKU");
    }

    @Test
    @DisplayName(
            "GIVEN both a leading and a trailing comment WHEN read SHOULD prefer the leading one, which is the documentation")
    void given_bothComments_when_read_should_preferLeading() {
        FileDescriptorProto file = commented().toBuilder()
                .setSourceCodeInfo(SourceCodeInfo.newBuilder()
                        .addLocation(SourceCodeInfo.Location.newBuilder()
                                .addAllPath(List.of(4, 0, 2, 0))
                                .setLeadingComments(" Stock keeping unit.\n")
                                .setTrailingComments(" an aside\n")))
                .build();

        assertThat(ProtoComments.of(file)).containsEntry("shop.v1.Item.sku", "Stock keeping unit.");
    }

    @Test
    @DisplayName("GIVEN a blank comment WHEN read SHOULD skip it rather than storing an empty description")
    void given_blankComment_when_read_should_skipIt() {
        FileDescriptorProto file = commented().toBuilder()
                .setSourceCodeInfo(SourceCodeInfo.newBuilder().addLocation(at("  \n \n", 6, 0)))
                .build();

        assertThat(ProtoComments.of(file)).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a file with no package WHEN read SHOULD key by the bare name without a leading dot")
    void given_noPackage_when_read_should_omitThePrefix() {
        FileDescriptorProto file = commented().toBuilder().clearPackage().build();

        assertThat(ProtoComments.of(file)).containsKey("CatalogService");
    }
}
