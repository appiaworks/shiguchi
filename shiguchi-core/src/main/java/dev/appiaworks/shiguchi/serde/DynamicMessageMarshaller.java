package dev.appiaworks.shiguchi.serde;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import io.grpc.Drainable;
import io.grpc.KnownLength;
import io.grpc.MethodDescriptor.Marshaller;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * gRPC Marshaller for DynamicMessage — used when the compiled Java stub is not available.
 * Serialises to/from the wire-format binary protobuf encoding.
 *
 * <p>{@link #stream} returns a {@link Drainable}/{@link KnownLength} InputStream (the same
 * contract as grpc-java's ProtoInputStream): when the transport drains it, the message is
 * written straight to the transport's OutputStream with no intermediate byte[] copy. The
 * byte array is materialised lazily only if a caller insists on InputStream-style reads.
 */
public class DynamicMessageMarshaller implements Marshaller<DynamicMessage> {

    private final Descriptor descriptor;

    private DynamicMessageMarshaller(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    public static DynamicMessageMarshaller forDescriptor(Descriptor descriptor) {
        return new DynamicMessageMarshaller(descriptor);
    }

    @Override
    public InputStream stream(DynamicMessage value) {
        return new DynamicMessageInputStream(value);
    }

    @Override
    public DynamicMessage parse(InputStream stream) {
        try {
            return DynamicMessage.parseFrom(descriptor, stream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse DynamicMessage for " + descriptor.getFullName(), e);
        }
    }

    /** Lazily-materialising message stream, drained without copying when the transport allows. */
    static final class DynamicMessageInputStream extends InputStream implements Drainable, KnownLength {

        private DynamicMessage message; // null once materialised or drained
        private ByteArrayInputStream buffer;

        DynamicMessageInputStream(DynamicMessage message) {
            this.message = message;
        }

        @Override
        public int drainTo(OutputStream target) throws IOException {
            if (message != null) {
                int size = message.getSerializedSize();
                message.writeTo(target);
                message = null;
                return size;
            }
            if (buffer == null) return 0;
            int remaining = buffer.available();
            buffer.transferTo(target);
            return remaining;
        }

        @Override
        public int read() {
            materialize();
            return buffer.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            materialize();
            return buffer.read(b, off, len);
        }

        @Override
        public int available() {
            if (message != null) return message.getSerializedSize();
            return buffer == null ? 0 : buffer.available();
        }

        private void materialize() {
            if (buffer == null) {
                buffer = new ByteArrayInputStream(message == null ? new byte[0] : message.toByteArray());
                message = null;
            }
        }
    }
}
