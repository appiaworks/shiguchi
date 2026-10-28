package dev.appiaworks.shiguchi.serde;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Timestamp;
import io.grpc.Drainable;
import io.grpc.KnownLength;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD tests for the zero-copy DynamicMessage marshaller stream: the transport-facing
 * Drainable/KnownLength contract must produce exactly the wire bytes, with the byte[]
 * materialised only when a caller falls back to InputStream reads.
 */
class DynamicMessageMarshallerTest {

    private static final Descriptor TS = Timestamp.getDescriptor();

    private static DynamicMessage sampleMessage() {
        return DynamicMessage.newBuilder(TS)
                .setField(TS.findFieldByName("seconds"), 1_700_000_000L)
                .setField(TS.findFieldByName("nanos"), 123)
                .build();
    }

    @Test
    @DisplayName(
            "Given a message stream, when the transport drains it, should write exactly the wire bytes and report the size")
    void given_messageStream_when_drained_should_writeExactWireBytes() throws Exception {
        DynamicMessage msg = sampleMessage();
        InputStream stream = DynamicMessageMarshaller.forDescriptor(TS).stream(msg);

        assertThat(stream).isInstanceOf(Drainable.class).isInstanceOf(KnownLength.class);
        assertThat(stream.available()).isEqualTo(msg.getSerializedSize());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int written = ((Drainable) stream).drainTo(out);

        assertThat(written).isEqualTo(msg.getSerializedSize());
        assertThat(out.toByteArray()).isEqualTo(msg.toByteArray());
        assertThat(stream.available()).isZero();
    }

    @Test
    @DisplayName("Given a message stream, when read as a plain InputStream, should yield the same wire bytes")
    void given_messageStream_when_readAsInputStream_should_yieldWireBytes() throws Exception {
        DynamicMessage msg = sampleMessage();
        InputStream stream = DynamicMessageMarshaller.forDescriptor(TS).stream(msg);

        byte[] read = stream.readAllBytes();

        assertThat(read).isEqualTo(msg.toByteArray());
    }

    @Test
    @DisplayName("Given a partially read stream, when drained, should write only the remaining bytes")
    void given_partiallyReadStream_when_drained_should_writeRemainingBytes() throws Exception {
        DynamicMessage msg = sampleMessage();
        InputStream stream = DynamicMessageMarshaller.forDescriptor(TS).stream(msg);
        byte[] wire = msg.toByteArray();

        int first = stream.read();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int written = ((Drainable) stream).drainTo(out);

        assertThat(first).isEqualTo(wire[0] & 0xFF);
        assertThat(written).isEqualTo(wire.length - 1);
        assertThat(out.toByteArray()).isEqualTo(java.util.Arrays.copyOfRange(wire, 1, wire.length));
    }

    @Test
    @DisplayName("Given a stream, when round-tripped through parse, should rebuild an equal message")
    void given_stream_when_parsedBack_should_rebuildEqualMessage() throws Exception {
        DynamicMessage msg = sampleMessage();
        DynamicMessageMarshaller marshaller = DynamicMessageMarshaller.forDescriptor(TS);

        DynamicMessage parsed = marshaller.parse(marshaller.stream(msg));

        assertThat(parsed).isEqualTo(msg);
    }
}
