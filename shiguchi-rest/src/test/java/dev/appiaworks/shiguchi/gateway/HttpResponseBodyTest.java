package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code google.api.http}'s {@code response_body}: sending one field of the response as the HTTP
 * body instead of the whole message.
 *
 * <p>The cases that matter are the ones where naive string handling would break — a string value
 * that itself looks like the key being extracted, and a value containing braces — because the
 * field is unwrapped out of printed JSON.
 */
@DisplayName("response_body")
class HttpResponseBodyTest {

    private static final FileDescriptor FILE = buildFile();
    private static final Descriptor LIST_RESPONSE = FILE.findMessageTypeByName("ListResponse");

    /** {@code ShiguchiFilter.responseBodyBytes(message, output, field)}. */
    private static String extract(DynamicMessage message, String field) throws Exception {
        Method m = ShiguchiFilter.class.getDeclaredMethod(
                "responseBodyBytes", DynamicMessage.class, Descriptor.class, String.class);
        m.setAccessible(true);
        try {
            return new String(
                    (byte[]) m.invoke(null, message, message.getDescriptorForType(), field), StandardCharsets.UTF_8);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static DynamicMessage.Builder response() {
        return DynamicMessage.newBuilder(LIST_RESPONSE);
    }

    private static DynamicMessage item(String sku, long count) {
        Descriptor item = FILE.findMessageTypeByName("Item");
        return DynamicMessage.newBuilder(item)
                .setField(item.findFieldByName("sku"), sku)
                .setField(item.findFieldByName("count"), count)
                .build();
    }

    @Test
    @DisplayName(
            "GIVEN response_body names a repeated field WHEN written SHOULD send a bare JSON array, not the wrapper")
    void given_repeatedField_when_written_should_sendABareArray() throws Exception {
        DynamicMessage message = response()
                .addRepeatedField(LIST_RESPONSE.findFieldByName("items"), item("A100", 2))
                .build();

        // The whole point: the RPC keeps its wrapper message, the HTTP caller gets the array.
        assertThat(extract(message, "items")).isEqualTo("[{\"sku\":\"A100\",\"count\":\"2\"}]");
    }

    @Test
    @DisplayName(
            "GIVEN a 64-bit field inside the projected value WHEN written SHOULD still render as a string, as proto3 JSON requires")
    void given_int64_when_written_should_followProto3Json() throws Exception {
        DynamicMessage message = response()
                .addRepeatedField(LIST_RESPONSE.findFieldByName("items"), item("A100", 9007199254740993L))
                .build();

        assertThat(extract(message, "items")).contains("\"count\":\"9007199254740993\"");
    }

    @Test
    @DisplayName("GIVEN response_body names a scalar WHEN written SHOULD send just that value")
    void given_scalarField_when_written_should_sendTheValue() throws Exception {
        DynamicMessage message = response()
                .setField(LIST_RESPONSE.findFieldByName("next_page_token"), "abc")
                .build();

        assertThat(extract(message, "next_page_token")).isEqualTo("\"abc\"");
    }

    @Test
    @DisplayName(
            "GIVEN a string value that looks like the JSON key being extracted WHEN written SHOULD still extract the right field")
    void given_valueResemblingTheKey_when_written_should_extractCorrectly() throws Exception {
        // A naive indexOf on the key would find this value's contents rather than the field, and
        // return a truncated body. The field is emitted before this one in field order.
        DynamicMessage message = response()
                .setField(LIST_RESPONSE.findFieldByName("next_page_token"), "\"items\":[{\"fake\":1}]")
                .build();

        assertThat(extract(message, "next_page_token")).isEqualTo("\"\\\"items\\\":[{\\\"fake\\\":1}]\"");
    }

    @Test
    @DisplayName("GIVEN an empty repeated field WHEN written SHOULD send an empty array rather than nothing")
    void given_emptyRepeated_when_written_should_sendEmptyArray() throws Exception {
        assertThat(extract(response().build(), "items")).isEqualTo("[]");
    }

    @Test
    @DisplayName(
            "GIVEN response_body names a field the message does not have WHEN written SHOULD fail loudly rather than silently sending everything")
    void given_unknownField_when_written_should_fail() {
        // A response_body naming a missing field is a proto bug; quietly sending the whole
        // message would hide it behind a response that merely looks wrong.
        assertThatThrownBy(() -> extract(response().build(), "no_such_field"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no_such_field");
    }

    @Test
    @DisplayName(
            "GIVEN response_body names a google.type.Date field WHEN written SHOULD send the ISO date, as the full response would")
    void given_mappedDateField_when_written_should_sendIsoText() throws Exception {
        Descriptor event = DATED.findMessageTypeByName("Event");
        DynamicMessage message = DynamicMessage.newBuilder(event)
                .setField(event.findFieldByName("day"), date(2026, 9, 22))
                .build();

        assertThat(extract(message, "day")).isEqualTo("\"2026-09-22\"");
    }

    @Test
    @DisplayName(
            "GIVEN response_body names a repeated google.type.Date field WHEN written SHOULD send an array of ISO dates")
    void given_repeatedMappedDates_when_written_should_sendArrayOfIsoText() throws Exception {
        Descriptor event = DATED.findMessageTypeByName("Event");
        DynamicMessage message = DynamicMessage.newBuilder(event)
                .addRepeatedField(event.findFieldByName("holidays"), date(2026, 12, 25))
                .addRepeatedField(event.findFieldByName("holidays"), date(0, 1, 1))
                .build();

        assertThat(extract(message, "holidays")).isEqualTo("[\"2026-12-25\",\"--01-01\"]");
    }

    private static final FileDescriptor DATED = buildDatedFile();

    private static DynamicMessage date(int year, int month, int day) {
        return DynamicMessage.newBuilder(com.google.type.Date.newBuilder()
                        .setYear(year)
                        .setMonth(month)
                        .setDay(day)
                        .build())
                .build();
    }

    private static FileDescriptor buildDatedFile() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("t/dated.proto")
                .setPackage("t")
                .setSyntax("proto3")
                .addDependency("google/type/date.proto")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Event")
                        .addField(field("day", 1, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.type.Date"))
                        .addField(field("holidays", 2, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".google.type.Date")
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {com.google.type.DateProto.getDescriptor()});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileDescriptor buildFile() {
        FileDescriptorProto proto = FileDescriptorProto.newBuilder()
                .setName("t/list.proto")
                .setPackage("t")
                .setSyntax("proto3")
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("Item")
                        .addField(field("sku", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("count", 2, FieldDescriptorProto.Type.TYPE_INT64)))
                .addMessageType(DescriptorProto.newBuilder()
                        .setName("ListResponse")
                        .addField(field("next_page_token", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("items", 2, FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName(".t.Item")
                                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)))
                .build();
        try {
            return FileDescriptor.buildFrom(proto, new FileDescriptor[] {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }
}
