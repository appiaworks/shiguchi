package dev.appiaworks.shiguchi.proto.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Renders the Shiguchi option protos (their comments are the documentation) into
 * docs/reference/proto-options.md and fails when the committed page differs. Regenerate with
 * {@code ./gradlew :shiguchi-proto:test -Dshiguchi.docs.update=true}.
 */
class ProtoOptionsReferenceTest {

    private static final List<String> FILES = List.of(
            "shiguchi/graphql.proto",
            "shiguchi/method.proto",
            "shiguchi/merge.proto",
            "shiguchi/topic.proto",
            "shiguchi/pagination.proto");

    @Test
    @DisplayName("GIVEN the option protos WHEN rendered SHOULD match docs/reference/proto-options.md")
    void GIVEN_option_protos_WHEN_rendered_SHOULD_match_the_committed_reference() throws IOException {
        FileDescriptorSet set =
                FileDescriptorSet.parseFrom(Files.readAllBytes(Path.of(System.getProperty("shiguchi.descriptors"))));
        String rendered = render(set);
        Path doc = Path.of(System.getProperty("shiguchi.protoOptionsDoc"));
        if (System.getProperty("shiguchi.docs.update") != null) {
            Files.writeString(doc, rendered);
        }
        assertThat(Files.readString(doc).replace("\r\n", "\n"))
                .as("docs/reference/proto-options.md is stale: run the test with -Dshiguchi.docs.update=true")
                .isEqualTo(rendered);
    }

    static String render(FileDescriptorSet set) {
        StringBuilder out = new StringBuilder();
        out.append("# Proto options reference\n\n")
                .append("Generated from the comments in `shiguchi/*.proto` by `ProtoOptionsReferenceTest`; ")
                .append("edit the proto comments, then run\n")
                .append("`./gradlew :shiguchi-proto:test -Dshiguchi.docs.update=true`. ")
                .append("The build fails when this page is out of date.\n\n")
                .append("Import `shiguchi/annotations.proto` for all of them, or one file on its own.\n");
        for (String name : FILES) {
            FileDescriptorProto file = set.getFileList().stream()
                    .filter(f -> f.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(name + " is not in the descriptor set"));
            Map<List<Integer>, String> comments = comments(file.getSourceCodeInfo());
            out.append("\n## `").append(name).append("`\n");
            for (int i = 0; i < file.getExtensionCount(); i++) {
                FieldDescriptorProto ext = file.getExtension(i);
                if (i == 0) {
                    out.append("\n| Option | Applies to | Message |\n| --- | --- | --- |\n");
                }
                out.append("| `(")
                        .append(file.getPackage())
                        .append('.')
                        .append(ext.getName())
                        .append(")` | `")
                        .append(simple(ext.getExtendee()))
                        .append("` | `")
                        .append(simple(ext.getTypeName()))
                        .append("` |\n");
            }
            for (int i = 0; i < file.getMessageTypeCount(); i++) {
                message(out, file.getMessageType(i), List.of(4, i), comments, "###");
            }
            for (int i = 0; i < file.getEnumTypeCount(); i++) {
                enumeration(out, file.getEnumType(i), List.of(5, i), comments, "###");
            }
        }
        return out.toString();
    }

    private static void message(
            StringBuilder out, DescriptorProto m, List<Integer> path, Map<List<Integer>, String> comments, String h) {
        out.append("\n").append(h).append(" `").append(m.getName()).append("`\n");
        String doc = comments.get(path);
        if (doc != null) {
            out.append("\n").append(doc).append("\n");
        }
        if (m.getFieldCount() > 0) {
            out.append("\n| Field | Type | Description |\n| --- | --- | --- |\n");
            for (int i = 0; i < m.getFieldCount(); i++) {
                FieldDescriptorProto f = m.getField(i);
                out.append("| `")
                        .append(f.getName())
                        .append("` | `")
                        .append(f.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED ? "repeated " : "")
                        .append(typeName(f))
                        .append("` | ")
                        .append(cell(comments.get(concat(path, 2, i))))
                        .append(" |\n");
            }
        }
        for (int i = 0; i < m.getEnumTypeCount(); i++) {
            enumeration(out, m.getEnumType(i), concat(path, 4, i), comments, h + "#");
        }
        for (int i = 0; i < m.getNestedTypeCount(); i++) {
            message(out, m.getNestedType(i), concat(path, 3, i), comments, h + "#");
        }
    }

    private static void enumeration(
            StringBuilder out,
            EnumDescriptorProto e,
            List<Integer> path,
            Map<List<Integer>, String> comments,
            String h) {
        out.append("\n").append(h).append(" `").append(e.getName()).append("` (enum)\n");
        String doc = comments.get(path);
        if (doc != null) {
            out.append("\n").append(doc).append("\n");
        }
        out.append("\n| Value | Description |\n| --- | --- |\n");
        for (int i = 0; i < e.getValueCount(); i++) {
            out.append("| `")
                    .append(e.getValue(i).getName())
                    .append("` | ")
                    .append(cell(comments.get(concat(path, 2, i))))
                    .append(" |\n");
        }
    }

    private static String typeName(FieldDescriptorProto f) {
        return switch (f.getType()) {
            case TYPE_MESSAGE, TYPE_ENUM -> simple(f.getTypeName());
            default -> f.getType().name().substring(5).toLowerCase();
        };
    }

    private static String simple(String fq) {
        return fq.substring(fq.lastIndexOf('.') + 1);
    }

    private static String cell(String comment) {
        return comment == null
                ? ""
                : comment.replace("\n", " ")
                        .replace("|", "\\|")
                        .replaceAll("\\s+", " ")
                        .trim();
    }

    private static List<Integer> concat(List<Integer> path, int a, int b) {
        List<Integer> out = new ArrayList<>(path);
        out.add(a);
        out.add(b);
        return out;
    }

    private static Map<List<Integer>, String> comments(SourceCodeInfo info) {
        Map<List<Integer>, String> out = new HashMap<>();
        for (SourceCodeInfo.Location l : info.getLocationList()) {
            if (l.hasLeadingComments()) {
                String text = l.getLeadingComments()
                        .lines()
                        .map(line -> line.startsWith(" ") ? line.substring(1) : line)
                        .reduce((x, y) -> x + "\n" + y)
                        .orElse("")
                        .strip();
                out.put(l.getPathList(), text);
            }
        }
        return out;
    }
}
