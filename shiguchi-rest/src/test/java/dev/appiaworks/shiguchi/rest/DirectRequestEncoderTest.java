package dev.appiaworks.shiguchi.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for DirectRequestEncoder: the request message's wire bytes must parse to the same
 * message that the reflective GrpcRequestBuilder + ProtoMapConverter path builds, or the encoder
 * returns null for the caller to use that path.
 */
class DirectRequestEncoderTest {

    private static Descriptor req;
    private static Descriptor filter;
    private static Descriptor inner;

    private static final DirectRequestEncoder encoder = new DirectRequestEncoder(false);
    private static final GrpcRequestBuilder builder = new GrpcRequestBuilder(false, false);

    @BeforeAll
    static void buildDescriptors() throws Exception {
        // Inner { string v = 1; }
        DescriptorProto innerMsg = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(scalar("v", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();

        // Filter { string name = 1; int32 limit = 2; Inner inner = 3; }
        DescriptorProto filterMsg = DescriptorProto.newBuilder()
                .setName("Filter")
                .addField(scalar("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addField(scalar("limit", 2, FieldDescriptorProto.Type.TYPE_INT32))
                .addField(message("inner", 3, ".test.Inner"))
                .build();

        // Color enum { RED = 0; GREEN = 1; }
        EnumDescriptorProto colorEnum = EnumDescriptorProto.newBuilder()
                .setName("Color")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("GREEN").setNumber(1))
                .build();

        // Req {
        //   string id = 1;
        //   int32 count = 2;
        //   bool active = 3;
        //   double score = 4;
        //   repeated string tags = 5;
        //   repeated int32 nums = 6;
        //   Filter filter = 7;
        //   Color color = 8;
        //   map<string,string> labels = 9;
        //   int64 big = 10;
        // }
        DescriptorProto.Builder reqMsg = DescriptorProto.newBuilder().setName("Req");
        reqMsg.addField(scalar("id", 1, FieldDescriptorProto.Type.TYPE_STRING));
        reqMsg.addField(scalar("count", 2, FieldDescriptorProto.Type.TYPE_INT32));
        reqMsg.addField(scalar("active", 3, FieldDescriptorProto.Type.TYPE_BOOL));
        reqMsg.addField(scalar("score", 4, FieldDescriptorProto.Type.TYPE_DOUBLE));
        reqMsg.addField(repeated(scalar("tags", 5, FieldDescriptorProto.Type.TYPE_STRING)));
        reqMsg.addField(repeated(scalar("nums", 6, FieldDescriptorProto.Type.TYPE_INT32)));
        reqMsg.addField(message("filter", 7, ".test.Filter"));
        reqMsg.addField(enumField("color", 8, ".test.Color"));

        reqMsg.addNestedType(mapEntry(
                "LabelsEntry",
                scalar("key", 1, FieldDescriptorProto.Type.TYPE_STRING),
                scalar("value", 2, FieldDescriptorProto.Type.TYPE_STRING)));
        reqMsg.addField(repeated(message("labels", 9, ".test.Req.LabelsEntry")));
        reqMsg.addField(scalar("big", 10, FieldDescriptorProto.Type.TYPE_INT64));

        FileDescriptor file = FileDescriptor.buildFrom(
                FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setSyntax("proto3")
                        .setPackage("test")
                        .addEnumType(colorEnum)
                        .addMessageType(innerMsg)
                        .addMessageType(filterMsg)
                        .addMessageType(reqMsg)
                        .build(),
                new FileDescriptor[0]);

        req = file.findMessageTypeByName("Req");
        filter = file.findMessageTypeByName("Filter");
        inner = file.findMessageTypeByName("Inner");
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

    private static FieldDescriptorProto enumField(String name, int number, String typeName) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                .setTypeName(typeName)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static FieldDescriptorProto repeated(FieldDescriptorProto field) {
        return field.toBuilder()
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                .build();
    }

    private static DescriptorProto mapEntry(String name, FieldDescriptorProto key, FieldDescriptorProto value) {
        return DescriptorProto.newBuilder()
                .setName(name)
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .addField(key)
                .addField(value)
                .build();
    }

    @Test
    @DisplayName("Given only a path variable id=order-1 and no body, when encoded, should equal the Map path")
    void given_pathVarIdOnly_when_encoded_should_equalMapPath() throws Exception {
        Map<String, String> pathVars = Map.of("id", "order-1");
        Map<String, String> queryParams = Map.of();

        byte[] encoded = encoder.encode(req, pathVars, queryParams, null, null);
        assertThat(encoded).isNotNull();

        DynamicMessage parsed = DynamicMessage.parseFrom(req, encoded);

        Map<String, Object> mapBuilt = builder.buildRequestMap(req, pathVars, queryParams, null);
        DynamicMessage expected = dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.dynamicFromMap(mapBuilt, req);

        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given query params count=5&active=true&score=1.5&color=GREEN&big=9007199254740993, when encoded, should equal Map path")
    void given_queryParamsScalars_when_encoded_should_equalMapPath() throws Exception {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of(
                "count", "5",
                "active", "true",
                "score", "1.5",
                "color", "GREEN",
                "big", "9007199254740993");

        byte[] encoded = encoder.encode(req, pathVars, queryParams, null, null);
        assertThat(encoded).isNotNull();

        DynamicMessage parsed = DynamicMessage.parseFrom(req, encoded);

        Map<String, Object> mapBuilt = builder.buildRequestMap(req, pathVars, queryParams, null);
        DynamicMessage expected = dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.dynamicFromMap(mapBuilt, req);

        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given dotted query params filter.name=x and filter.inner.v=y plus path var filter.limit=3, when encoded, should equal Map path")
    void given_dottedNestedParams_when_encoded_should_equalMapPath() throws Exception {
        Map<String, String> pathVars = Map.of("filter.limit", "3");
        Map<String, String> queryParams = Map.of("filter.name", "x", "filter.inner.v", "y");

        byte[] encoded = encoder.encode(req, pathVars, queryParams, null, null);
        assertThat(encoded).isNotNull();

        DynamicMessage parsed = DynamicMessage.parseFrom(req, encoded);

        Map<String, Object> mapBuilt = builder.buildRequestMap(req, pathVars, queryParams, null);
        DynamicMessage expected = dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.dynamicFromMap(mapBuilt, req);

        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given JSON body {\"id\":\"a\",\"count\":1,\"filter\":{\"name\":\"n\",\"limit\":2},\"tags\":[\"x\",\"y\"],\"labels\":{\"k\":\"v\"}} with path var id=b, when encoded, should have path var win")
    void given_bodyWithPathVarOverride_when_encoded_should_equalMapPath() throws Exception {
        Map<String, String> pathVars = Map.of("id", "b");
        Map<String, String> queryParams = Map.of();
        String body =
                "{\"id\":\"a\",\"count\":1,\"filter\":{\"name\":\"n\",\"limit\":2},\"tags\":[\"x\",\"y\"],\"labels\":{\"k\":\"v\"}}";

        byte[] encoded = encoder.encode(req, pathVars, queryParams, body, null);
        assertThat(encoded).isNotNull();

        DynamicMessage parsed = DynamicMessage.parseFrom(req, encoded);

        Map<String, Object> mapBuilt = builder.buildRequestMap(req, pathVars, queryParams, body);
        DynamicMessage expected = dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.dynamicFromMap(mapBuilt, req);

        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given a body that sets filter and a query param filter.name=q (scalar inside a message the body set), when encoded, should equal the Map path")
    void given_scalarQueryParamInsideBodyMessage_when_encoded_should_equalMapPath() throws Exception {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of("filter.name", "q");
        String body = "{\"filter\":{\"limit\":2}}";

        byte[] encoded = encoder.encode(req, pathVars, queryParams, body, null);
        assertThat(encoded).isNotNull();

        DynamicMessage parsed = DynamicMessage.parseFrom(req, encoded);

        Map<String, Object> mapBuilt = builder.buildRequestMap(req, pathVars, queryParams, body);
        DynamicMessage expected = dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.dynamicFromMap(mapBuilt, req);

        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "Given a body that sets tags and a query param tags=z (repeated field the body set), when encoded, should return null")
    void given_repeatedFieldBothBodyAndQuery_when_encoded_should_returnNull() {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of("tags", "z");
        String body = "{\"tags\":[\"x\",\"y\"]}";

        byte[] encoded = encoder.encode(req, pathVars, queryParams, body, null);

        assertThat(encoded).isNull();
    }

    @Test
    @DisplayName("Given a query param targeting a repeated message field (labels=x), when encoded, should return null")
    void given_queryParamForMapField_when_encoded_should_returnNull() {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of("labels", "x");

        byte[] encoded = encoder.encode(req, pathVars, queryParams, null, null);

        assertThat(encoded).isNull();
    }

    @Test
    @DisplayName("Given a gateway convention param pageSize=10, when encoded, should return null")
    void given_gatewayConventionParam_when_encoded_should_returnNull() {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of("pageSize", "10");

        byte[] encoded = encoder.encode(req, pathVars, queryParams, null, null);

        assertThat(encoded).isNull();
    }

    @Test
    @DisplayName("Given a named body field (bodyField \"filter\"), when encoded, should return null")
    void given_namedBodyField_when_encoded_should_returnNull() {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of();
        String body = "{\"limit\":2}";

        byte[] encoded = encoder.encode(req, pathVars, queryParams, body, "filter");

        assertThat(encoded).isNull();
    }

    @Test
    @DisplayName("Given a query param count=1.5 (fraction for an int field), when encoded, should return null")
    void given_fractionForIntField_when_encoded_should_returnNull() {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of("count", "1.5");

        byte[] encoded = encoder.encode(req, pathVars, queryParams, null, null);

        assertThat(encoded).isNull();
    }

    @Test
    @DisplayName("Given an unknown query param foo=bar, when encoded, should be ignored and equal the Map path")
    void given_unknownQueryParam_when_encoded_should_beIgnoredAndEqualMapPath() throws Exception {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of("foo", "bar", "id", "test-1");

        byte[] encoded = encoder.encode(req, pathVars, queryParams, null, null);
        assertThat(encoded).isNotNull();

        DynamicMessage parsed = DynamicMessage.parseFrom(req, encoded);

        Map<String, Object> mapBuilt = builder.buildRequestMap(req, pathVars, queryParams, null);
        DynamicMessage expected = dev.appiaworks.protobuf.extras.serde.ProtoMapConverter.dynamicFromMap(mapBuilt, req);

        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    @DisplayName("Given a body with a duplicate key, when encoded, should return null")
    void given_bodyWithDuplicateKey_when_encoded_should_returnNull() {
        Map<String, String> pathVars = Map.of();
        Map<String, String> queryParams = Map.of();
        String body = "{\"id\":\"first\",\"id\":\"second\"}";

        byte[] encoded = encoder.encode(req, pathVars, queryParams, body, null);

        assertThat(encoded).isNull();
    }
}
