package dev.appiaworks.shiguchi.gateway;

import io.grpc.Metadata;
import io.grpc.Status;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The gRPC-Web wire format
 * (<a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md">PROTOCOL-WEB</a>): the
 * gRPC length-prefixed frames — one flag byte, a 4-byte big-endian length, the payload — over a
 * plain HTTP body, with the trailers sent as one last frame whose flag has the high bit set
 * ({@code 0x80}) and whose payload is an HTTP/1-style header block.
 *
 * <p>The {@code -text} variants base64 the body. On the way in, a text body is decoded as a whole;
 * a client may send it as several padded base64 chunks, so decoding restarts after each padding
 * run. On the way out, each frame is encoded on its own — every chunk a whole number of base64
 * quads — which is what lets a browser decode a stream as it arrives.
 */
final class GrpcWebFrames {

    static final byte DATA = 0x00;
    static final byte COMPRESSED = 0x01;
    static final byte TRAILERS = (byte) 0x80;

    private static final Metadata.Key<byte[]> STATUS_DETAILS =
            Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER);

    private GrpcWebFrames() {}

    /** A request body that is not a single well-formed data frame. */
    static final class MalformedFrameException extends RuntimeException {
        MalformedFrameException(String message) {
            super(message);
        }
    }

    /**
     * The one message a unary or server-streaming gRPC-Web request carries. An empty body is an
     * empty message. A compressed frame is refused: the gateway does not negotiate
     * {@code grpc-encoding} on this path.
     */
    static byte[] requestMessage(byte[] body, boolean text) {
        byte[] raw = text ? decodeBase64Chunks(body) : body;
        if (raw.length == 0) return new byte[0];
        if (raw.length < 5) throw new MalformedFrameException("gRPC-Web frame shorter than its 5-byte header");
        byte flag = raw[0];
        if ((flag & COMPRESSED) != 0) {
            throw new MalformedFrameException("compressed gRPC-Web frames are not supported");
        }
        if ((flag & TRAILERS) != 0) throw new MalformedFrameException("request starts with a trailers frame");
        long length = ((raw[1] & 0xFFL) << 24) | ((raw[2] & 0xFFL) << 16) | ((raw[3] & 0xFFL) << 8) | (raw[4] & 0xFFL);
        if (length > raw.length - 5L) throw new MalformedFrameException("gRPC-Web frame truncated");
        if (length < raw.length - 5L) {
            throw new MalformedFrameException("more than one message in a unary or server-streaming request");
        }
        byte[] message = new byte[(int) length];
        System.arraycopy(raw, 5, message, 0, (int) length);
        return message;
    }

    /** One data frame. */
    static byte[] dataFrame(byte[] message) {
        return frame(DATA, message);
    }

    /**
     * The trailers frame: {@code grpc-status}, a percent-encoded {@code grpc-message} when there
     * is one, and {@code grpc-status-details-bin} when the backend attached rich details.
     */
    static byte[] trailerFrame(Status status, Metadata trailers) {
        StringBuilder block = new StringBuilder();
        block.append("grpc-status:").append(status.getCode().value()).append("\r\n");
        if (status.getDescription() != null && !status.getDescription().isEmpty()) {
            block.append("grpc-message:")
                    .append(percentEncode(status.getDescription()))
                    .append("\r\n");
        }
        if (trailers != null) {
            byte[] details = trailers.get(STATUS_DETAILS);
            if (details != null) {
                block.append("grpc-status-details-bin:")
                        .append(Base64.getEncoder().withoutPadding().encodeToString(details))
                        .append("\r\n");
            }
        }
        return frame(TRAILERS, block.toString().getBytes(StandardCharsets.US_ASCII));
    }

    /** A frame as the {@code -text} variant sends it: base64, padded, on its own. */
    static byte[] text(byte[] frame) {
        return Base64.getEncoder().encode(frame);
    }

    private static byte[] frame(byte flag, byte[] payload) {
        byte[] out = new byte[5 + payload.length];
        out[0] = flag;
        out[1] = (byte) (payload.length >>> 24);
        out[2] = (byte) (payload.length >>> 16);
        out[3] = (byte) (payload.length >>> 8);
        out[4] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 5, payload.length);
        return out;
    }

    /** Base64 that may be several padded chunks back to back ({@code "AAA=BBBB"}), whitespace ignored. */
    static byte[] decodeBase64Chunks(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        StringBuilder chunk = new StringBuilder();
        boolean padding = false;
        try {
            for (byte b : body) {
                char c = (char) (b & 0xFF);
                if (Character.isWhitespace(c)) continue;
                if (c == '=') {
                    padding = true;
                } else if (padding) {
                    // A padding run ended: the chunk before it is complete.
                    out.writeBytes(Base64.getDecoder().decode(chunk.toString()));
                    chunk.setLength(0);
                    padding = false;
                }
                chunk.append(c);
            }
            if (!chunk.isEmpty()) out.writeBytes(Base64.getDecoder().decode(chunk.toString()));
        } catch (IllegalArgumentException e) {
            throw new MalformedFrameException("gRPC-Web text body is not valid base64");
        }
        return out.toByteArray();
    }

    /** {@code grpc-message} is percent-encoded: printable ASCII except {@code %} passes through. */
    static String percentEncode(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v >= 0x20 && v < 0x7F && v != '%') {
                out.append((char) v);
            } else {
                out.append('%')
                        .append(Character.toUpperCase(Character.forDigit(v >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
