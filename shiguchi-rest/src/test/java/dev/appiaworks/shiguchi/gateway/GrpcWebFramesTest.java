package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the gRPC-Web wire format frame handling in {@link GrpcWebFrames}.
 */
@DisplayName("gRPC-Web frame handling")
class GrpcWebFramesTest {

    @Test
    @DisplayName("GIVEN a binary request frame WHEN parsed SHOULD extract the message body")
    void given_binaryRequestFrame_when_parsed_should_extractMessage() {
        byte[] message = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] frame = GrpcWebFrames.dataFrame(message);

        byte[] extracted = GrpcWebFrames.requestMessage(frame, false);

        assertThat(extracted).isEqualTo(message);
    }

    @Test
    @DisplayName("GIVEN an empty request body WHEN parsed SHOULD return an empty message")
    void given_emptyRequestBody_when_parsed_should_returnEmpty() {
        byte[] extracted = GrpcWebFrames.requestMessage(new byte[0], false);

        assertThat(extracted).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a text-encoded base64 frame WHEN parsed SHOULD decode to message")
    void given_textEncodedFrame_when_parsed_should_decodeBase64() {
        byte[] message = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] frame = GrpcWebFrames.dataFrame(message);
        byte[] textBody = GrpcWebFrames.text(frame);

        byte[] extracted = GrpcWebFrames.requestMessage(textBody, true);

        assertThat(extracted).isEqualTo(message);
    }

    @Test
    @DisplayName("GIVEN two separate padded base64 chunks concatenated WHEN parsed SHOULD decode both together")
    void given_twoBase64Chunks_when_parsed_should_decodeBoth() {
        // Simulate a browser that encodes part of a frame, then more of the same frame
        byte[] message = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] frame = GrpcWebFrames.dataFrame(message);

        // Base64 encode the entire frame
        byte[] encoded = Base64.getEncoder().encode(frame);

        // Simulate splitting the base64 string at a padding boundary
        String encodedStr = new String(encoded, StandardCharsets.US_ASCII);
        int mid = encodedStr.length() / 2;
        String chunk1 = encodedStr.substring(0, mid);
        String chunk2 = encodedStr.substring(mid);

        // Concatenate the chunks (simulating browser sending two padded base64 chunks)
        byte[] combined = (chunk1 + chunk2).getBytes(StandardCharsets.US_ASCII);

        byte[] extracted = GrpcWebFrames.requestMessage(combined, true);

        assertThat(extracted).isEqualTo(message);
    }

    @Test
    @DisplayName("GIVEN a compressed frame flag WHEN parsed SHOULD throw MalformedFrameException")
    void given_compressedFlag_when_parsed_should_throwException() {
        byte[] message = "data".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[5 + message.length];
        frame[0] = GrpcWebFrames.COMPRESSED; // compressed flag
        frame[1] = 0;
        frame[2] = 0;
        frame[3] = 0;
        frame[4] = (byte) message.length;
        System.arraycopy(message, 0, frame, 5, message.length);

        assertThatThrownBy(() -> GrpcWebFrames.requestMessage(frame, false))
                .isInstanceOf(GrpcWebFrames.MalformedFrameException.class)
                .hasMessageContaining("compressed");
    }

    @Test
    @DisplayName("GIVEN a truncated frame WHEN parsed SHOULD throw MalformedFrameException")
    void given_truncatedFrame_when_parsed_should_throwException() {
        byte[] frame = new byte[3]; // less than 5 byte header

        assertThatThrownBy(() -> GrpcWebFrames.requestMessage(frame, false))
                .isInstanceOf(GrpcWebFrames.MalformedFrameException.class)
                .hasMessageContaining("shorter than");
    }

    @Test
    @DisplayName("GIVEN two frames in one body WHEN parsed SHOULD throw MalformedFrameException")
    void given_twoFramesInBody_when_parsed_should_throwException() {
        byte[] message1 = "first".getBytes(StandardCharsets.UTF_8);
        byte[] message2 = "second".getBytes(StandardCharsets.UTF_8);
        byte[] frame1 = GrpcWebFrames.dataFrame(message1);
        byte[] frame2 = GrpcWebFrames.dataFrame(message2);

        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        assertThatThrownBy(() -> GrpcWebFrames.requestMessage(combined, false))
                .isInstanceOf(GrpcWebFrames.MalformedFrameException.class)
                .hasMessageContaining("more than one message");
    }

    @Test
    @DisplayName(
            "GIVEN a status with description WHEN creating trailer frame SHOULD encode status and percent-encoded message")
    void given_statusWithDescription_when_trailerFrame_should_encodeStatusAndMessage() {
        Status status = Status.NOT_FOUND.withDescription("no such book 50%");

        byte[] trailer = GrpcWebFrames.trailerFrame(status, null);

        // Flag byte should be 0x80 (trailers)
        assertThat(trailer[0]).isEqualTo((byte) 0x80);

        String payload =
                new String(java.util.Arrays.copyOfRange(trailer, 5, trailer.length), StandardCharsets.US_ASCII);
        assertThat(payload).contains("grpc-status:5\r\n");
        assertThat(payload).contains("grpc-message:no such book 50%25\r\n");
    }

    @Test
    @DisplayName(
            "GIVEN a status with Metadata containing binary details WHEN creating trailer frame SHOULD encode details as base64-without-padding")
    void given_statusWithBinaryMetadata_when_trailerFrame_should_encodeDetailsAsBase64() {
        Metadata trailers = new Metadata();
        byte[] details = "binary-details".getBytes(StandardCharsets.UTF_8);
        Metadata.Key<byte[]> detailsKey = Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER);
        trailers.put(detailsKey, details);

        byte[] trailer = GrpcWebFrames.trailerFrame(Status.OK, trailers);

        String payload =
                new String(java.util.Arrays.copyOfRange(trailer, 5, trailer.length), StandardCharsets.US_ASCII);
        String expectedBase64 = Base64.getEncoder().withoutPadding().encodeToString(details);
        assertThat(payload).contains("grpc-status-details-bin:" + expectedBase64 + "\r\n");
    }

    @Test
    @DisplayName("GIVEN non-ASCII characters WHEN percent-encoding SHOULD encode UTF-8 bytes as percent sequences")
    void given_nonAsciiCharacters_when_percentEncode_should_encodeUtf8Bytes() {
        String input = "no such book é";

        String encoded = GrpcWebFrames.percentEncode(input);

        // 'é' in UTF-8 is C3 A9, which should be %C3%A9
        assertThat(encoded).contains("no such book %C3%A9");
    }

    @Test
    @DisplayName(
            "GIVEN a grpc-message header value with percent signs WHEN percent-encoding SHOULD preserve percent signs that are already percent-encoded")
    void given_percentSigns_when_percentEncode_should_preservePercent() {
        String input = "50%";

        String encoded = GrpcWebFrames.percentEncode(input);

        // % should be encoded as %25
        assertThat(encoded).isEqualTo("50%25");
    }
}
