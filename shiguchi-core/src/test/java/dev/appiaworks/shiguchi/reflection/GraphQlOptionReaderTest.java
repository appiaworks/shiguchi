package dev.appiaworks.shiguchi.reflection;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FieldOptions;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodOptions;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.UnknownFieldSet;
import dev.appiaworks.shiguchi.proto.FieldRule;
import dev.appiaworks.shiguchi.proto.GraphQlProto;
import dev.appiaworks.shiguchi.proto.GraphQlRule;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for GraphQlOptionReader.
 *
 * <p>The upstream {@code graphql} file is built from a descriptor proto, so nothing is vendored. The
 * 1079 option reaches the method either as an unknown field (reflection and descriptor-set loaders)
 * or as a known extension (generated code with that file on the classpath).
 */
class GraphQlOptionReaderTest {

    private static final GraphQlOptionReader ON = GraphQlOptionReader.of(List.of("graphql-1079"));

    // ---------------------------------------------------------------- fixtures

    private static FieldDescriptorProto field(String name, int number, Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto field(String name, int number, Type type, String typeName) {
        return field(name, number, type).toBuilder().setTypeName(typeName).build();
    }

    private static FieldDescriptorProto repeated(String name, int number, Type type) {
        return field(name, number, type).toBuilder()
                .setLabel(Label.LABEL_REPEATED)
                .build();
    }

    private static FieldDescriptorProto extension(String name, String extendee, Type type, String typeName) {
        var builder = field(name, 1079, type).toBuilder().setExtendee(extendee);
        if (typeName != null) builder.setTypeName(typeName);
        return builder.build();
    }

    private static FileDescriptor upstreamFile() {
        var proto = FileDescriptorProto.newBuilder()
                .setName("graphql.proto")
                .setPackage("graphql")
                .setSyntax("proto3")
                .addDependency("google/protobuf/descriptor.proto")
                .addEnumType(EnumDescriptorProto.newBuilder()
                        .setName("GraphqlType")
                        .addValue(EnumValueDescriptorProto.newBuilder()
                                .setName("QUERY")
                                .setNumber(0))
                        .addValue(EnumValueDescriptorProto.newBuilder()
                                .setName("MUTATION")
                                .setNumber(1))
                        .addValue(EnumValueDescriptorProto.newBuilder()
                                .setName("RESOLVER")
                                .setNumber(2))
                        .addValue(EnumValueDescriptorProto.newBuilder()
                                .setName("SUBSCRIPTION")
                                .setNumber(3)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("GraphqlRequest")
                        .addField(field("name", 1, Type.TYPE_STRING))
                        .addField(repeated("plucks", 2, Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("GraphqlResponse")
                        .addField(field("required", 1, Type.TYPE_BOOL))
                        .addField(field("pluck", 2, Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("GraphqlSchema")
                        .addField(field("type", 1, Type.TYPE_ENUM, ".graphql.GraphqlType"))
                        .addField(field("name", 2, Type.TYPE_STRING))
                        .addField(field("request", 3, Type.TYPE_MESSAGE, ".graphql.GraphqlRequest"))
                        .addField(field("response", 4, Type.TYPE_MESSAGE, ".graphql.GraphqlResponse")))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("GraphqlField")
                        .addField(field("required", 1, Type.TYPE_BOOL))
                        .addField(field("name", 2, Type.TYPE_STRING))
                        .addField(field("default", 3, Type.TYPE_STRING))
                        .addField(field("omit", 4, Type.TYPE_BOOL))
                        .addField(field("resolver", 5, Type.TYPE_STRING)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("GraphqlService")
                        .addField(field("host", 1, Type.TYPE_STRING))
                        .addField(field("insecure", 2, Type.TYPE_BOOL)))
                .addExtension(extension(
                        "schema", ".google.protobuf.MethodOptions", Type.TYPE_MESSAGE, ".graphql.GraphqlSchema"))
                .addExtension(
                        extension("field", ".google.protobuf.FieldOptions", Type.TYPE_MESSAGE, ".graphql.GraphqlField"))
                .addExtension(extension(
                        "service", ".google.protobuf.ServiceOptions", Type.TYPE_MESSAGE, ".graphql.GraphqlService"))
                .build();
        return build(proto, DescriptorProtos.getDescriptor());
    }

    private static FileDescriptor foreignFile() {
        var proto = FileDescriptorProto.newBuilder()
                .setName("other.proto")
                .setPackage("other")
                .setSyntax("proto3")
                .addDependency("google/protobuf/descriptor.proto")
                .addExtension(extension("tag", ".google.protobuf.MethodOptions", Type.TYPE_STRING, null))
                .build();
        return build(proto, DescriptorProtos.getDescriptor());
    }

    private static FileDescriptor build(FileDescriptorProto proto, FileDescriptor... deps) {
        try {
            return FileDescriptor.buildFrom(proto, deps);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A {@code test.proto} with {@code Req.id}, a service and one method {@code Do}, importing {@code imports}. */
    private static FileDescriptor service(
            MethodOptions methodOptions, FieldOptions fieldOptions, FileDescriptor... imports) {
        var proto = FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .setSyntax("proto3");
        for (FileDescriptor dep : imports) proto.addDependency(dep.getName());
        var id = field("id", 1, Type.TYPE_STRING).toBuilder().setOptions(fieldOptions);
        proto.addMessageType(DescriptorProto.newBuilder().setName("Req").addField(id))
                .addMessageType(DescriptorProto.newBuilder().setName("Res"))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Svc")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("Do")
                                .setInputType(".test.Req")
                                .setOutputType(".test.Res")
                                .setOptions(methodOptions)));
        return build(proto.build(), imports);
    }

    private static MethodDescriptor method(FileDescriptor file) {
        return file.findServiceByName("Svc").findMethodByName("Do");
    }

    private static FieldDescriptor reqId(FileDescriptor file) {
        return file.findMessageTypeByName("Req").findFieldByName("id");
    }

    private static FieldDescriptor fd(FileDescriptor upstream, String name) {
        return upstream.findMessageTypeByName("GraphqlField").findFieldByName(name);
    }

    private static DynamicMessage schema(
            FileDescriptor upstream, String type, String name, List<String> plucks, String responsePluck) {
        Descriptor schemaType = upstream.findMessageTypeByName("GraphqlSchema");
        Descriptor requestType = upstream.findMessageTypeByName("GraphqlRequest");
        Descriptor responseType = upstream.findMessageTypeByName("GraphqlResponse");
        var request = DynamicMessage.newBuilder(requestType);
        for (String pluck : plucks) {
            request.addRepeatedField(requestType.findFieldByName("plucks"), pluck);
        }
        var response =
                DynamicMessage.newBuilder(responseType).setField(responseType.findFieldByName("pluck"), responsePluck);
        return DynamicMessage.newBuilder(schemaType)
                .setField(
                        schemaType.findFieldByName("type"),
                        upstream.findEnumTypeByName("GraphqlType").findValueByName(type))
                .setField(schemaType.findFieldByName("name"), name)
                .setField(schemaType.findFieldByName("request"), request.build())
                .setField(schemaType.findFieldByName("response"), response.build())
                .build();
    }

    private static UnknownFieldSet unknown(ByteString value) {
        return UnknownFieldSet.newBuilder()
                .addField(
                        1079,
                        UnknownFieldSet.Field.newBuilder()
                                .addLengthDelimited(value)
                                .build())
                .build();
    }

    private static MethodOptions unknownOptions(DynamicMessage value) {
        return MethodOptions.newBuilder()
                .setUnknownFields(unknown(value.toByteString()))
                .build();
    }

    private static MethodOptions knownOptions(FileDescriptor upstream, DynamicMessage value) throws Exception {
        var registry = ExtensionRegistry.newInstance();
        registry.add(
                upstream.findExtensionByName("schema"),
                DynamicMessage.getDefaultInstance(upstream.findMessageTypeByName("GraphqlSchema")));
        return MethodOptions.parseFrom(unknownOptions(value).toByteArray(), registry);
    }

    private static MethodOptions mutationOptions(FileDescriptor upstream) {
        return unknownOptions(schema(upstream, "MUTATION", "createUser", List.of("user"), "user"));
    }

    private static FieldOptions fieldAlias(FileDescriptor upstream, Consumer<DynamicMessage.Builder> setter) {
        var builder = DynamicMessage.newBuilder(upstream.findMessageTypeByName("GraphqlField"));
        setter.accept(builder);
        return FieldOptions.newBuilder()
                .setUnknownFields(unknown(builder.build().toByteString()))
                .build();
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("GIVEN a graphql.schema unknown field WHEN alias is on SHOULD map it to a GraphQlRule")
    void given_unknown_field_schema_when_alias_on_should_map_to_rule() {
        var upstream = upstreamFile();
        var file = service(mutationOptions(upstream), FieldOptions.getDefaultInstance(), upstream);

        var result = ON.methodRule(method(file));

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(GraphQlOptionReader.GRAPHQL_1079);
        var rule = result.get().rule();
        assertThat(rule.getName()).isEqualTo("createUser");
        assertThat(rule.getType()).isEqualTo(GraphQlRule.Op.MUTATION);
        assertThat(rule.getArgFieldsList()).containsExactly("user");
        assertThat(rule.getPluck()).isEqualTo("user");
    }

    @Test
    @DisplayName("GIVEN a graphql.schema known extension WHEN alias is on SHOULD map it to a GraphQlRule")
    void given_known_extension_schema_when_alias_on_should_map_to_rule() throws Exception {
        var upstream = upstreamFile();
        var options = knownOptions(upstream, schema(upstream, "MUTATION", "createUser", List.of("user"), "user"));
        assertThat(options.getAllFields().keySet()).anyMatch(FieldDescriptor::isExtension);
        var file = service(options, FieldOptions.getDefaultInstance(), upstream);

        var result = ON.methodRule(method(file));

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(GraphQlOptionReader.GRAPHQL_1079);
        var rule = result.get().rule();
        assertThat(rule.getName()).isEqualTo("createUser");
        assertThat(rule.getType()).isEqualTo(GraphQlRule.Op.MUTATION);
        assertThat(rule.getArgFieldsList()).containsExactly("user");
        assertThat(rule.getPluck()).isEqualTo("user");
    }

    @Test
    @DisplayName("GIVEN graphql options WHEN alias is off SHOULD ignore them and report the switch state")
    void given_graphql_options_when_alias_off_should_ignore_them() {
        var upstream = upstreamFile();
        var file = service(
                mutationOptions(upstream),
                fieldAlias(upstream, b -> b.setField(fd(upstream, "name"), "userId")),
                upstream);

        for (var reader : List.of(GraphQlOptionReader.shiguchiOnly(), GraphQlOptionReader.of(List.of()))) {
            assertThat(reader.readsGraphql1079()).isFalse();
            assertThat(reader.methodRule(method(file))).isEmpty();
            assertThat(reader.fieldRule(reqId(file))).isEqualTo(FieldRule.getDefaultInstance());
        }
        assertThat(GraphQlOptionReader.of(List.of(" GraphQL-1079 ")).readsGraphql1079())
                .isTrue();
    }

    @Test
    @DisplayName("GIVEN both Shiguchi and graphql options WHEN alias is on SHOULD prefer the Shiguchi option")
    void given_both_options_when_alias_on_should_prefer_shiguchi() {
        var upstream = upstreamFile();
        var options = mutationOptions(upstream).toBuilder()
                .setExtension(
                        GraphQlProto.graphql,
                        GraphQlRule.newBuilder().setName("fromShiguchi").build())
                .build();
        var file = service(options, FieldOptions.getDefaultInstance(), upstream);

        var result = ON.methodRule(method(file));

        assertThat(result).isPresent();
        assertThat(result.get().source()).isEqualTo(GraphQlOptionReader.SOURCE_SHIGUCHI);
        assertThat(result.get().rule().getName()).isEqualTo("fromShiguchi");
    }

    @Test
    @DisplayName("GIVEN upstream enum types WHEN mapped SHOULD match by name, not by number")
    void given_enum_types_when_mapped_should_match_by_name() {
        var upstream = upstreamFile();
        var subscription = service(
                unknownOptions(schema(upstream, "SUBSCRIPTION", "onUser", List.of(), "")),
                FieldOptions.getDefaultInstance(),
                upstream);
        var query = service(
                unknownOptions(schema(upstream, "QUERY", "user", List.of(), "")),
                FieldOptions.getDefaultInstance(),
                upstream);

        assertThat(ON.methodRule(method(subscription)).orElseThrow().rule().getType())
                .isEqualTo(GraphQlRule.Op.SUBSCRIPTION);
        assertThat(ON.methodRule(method(query)).orElseThrow().rule().getType()).isEqualTo(GraphQlRule.Op.QUERY);
    }

    @Test
    @DisplayName("GIVEN an upstream RESOLVER type WHEN alias is on SHOULD ignore the option")
    void given_resolver_type_when_alias_on_should_be_ignored() {
        var upstream = upstreamFile();
        var file = service(
                unknownOptions(schema(upstream, "RESOLVER", "friends", List.of(), "")),
                FieldOptions.getDefaultInstance(),
                upstream);

        assertThat(ON.methodRule(method(file))).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a foreign extension 1079 WHEN the file does not import graphql SHOULD ignore it")
    void given_foreign_extension_1079_when_no_graphql_import_should_be_empty() {
        var foreign = foreignFile();
        var options = MethodOptions.newBuilder()
                .setUnknownFields(unknown(ByteString.copyFromUtf8("hello")))
                .build();
        var file = service(options, FieldOptions.getDefaultInstance(), foreign);

        assertThat(ON.methodRule(method(file))).isEmpty();
        assertThat(ON.fieldRule(reqId(file))).isEqualTo(FieldRule.getDefaultInstance());
    }

    @Test
    @DisplayName("GIVEN a graphql.field option WHEN alias is on SHOULD map it to a FieldRule, and not when off")
    void given_graphql_field_when_alias_on_should_map_to_field_rule() {
        var upstream = upstreamFile();
        var options = fieldAlias(
                upstream,
                b -> b.setField(fd(upstream, "required"), true)
                        .setField(fd(upstream, "name"), "userId")
                        .setField(fd(upstream, "default"), "x")
                        .setField(fd(upstream, "omit"), false));
        var file = service(MethodOptions.getDefaultInstance(), options, upstream);

        var rule = ON.fieldRule(reqId(file));

        assertThat(rule.getRequired()).isTrue();
        assertThat(rule.getName()).isEqualTo("userId");
        assertThat(rule.getDefault()).isEqualTo("x");
        assertThat(rule.getOmit()).isFalse();
        assertThat(GraphQlOptionReader.shiguchiOnly().fieldRule(reqId(file))).isEqualTo(FieldRule.getDefaultInstance());
    }

    @Test
    @DisplayName("GIVEN only an unmapped graphql.field resolver WHEN alias is on SHOULD yield an empty rule")
    void given_only_resolver_when_alias_on_should_yield_empty_rule() {
        var upstream = upstreamFile();
        var options = fieldAlias(upstream, b -> b.setField(fd(upstream, "resolver"), "Foo"));
        var file = service(MethodOptions.getDefaultInstance(), options, upstream);

        assertThat(ON.fieldRule(reqId(file))).isEqualTo(FieldRule.getDefaultInstance());
    }
}
