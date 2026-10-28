package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.grpc.GrpcCallContext;
import dev.appiaworks.shiguchi.grpc.ResponseMetadataCapture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

@DisplayName("ResponseHeaderForwarder")
class ResponseHeaderForwarderTest {

    private static Metadata metadata(String... pairs) {
        Metadata md = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            md.put(Metadata.Key.of(pairs[i], Metadata.ASCII_STRING_MARSHALLER), pairs[i + 1]);
        }
        return md;
    }

    /** A capture filled the way the channel interceptor fills it on a real call. */
    private static ResponseMetadataCapture captured(Metadata headers, Metadata trailers) {
        var capture = new ResponseMetadataCapture();
        Channel channel = new Channel() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> m, CallOptions o) {
                return new ClientCall<>() {
                    @Override
                    public void start(Listener<RespT> listener, Metadata requestHeaders) {
                        if (headers != null) listener.onHeaders(headers);
                        listener.onClose(Status.OK, trailers != null ? trailers : new Metadata());
                    }

                    @Override
                    public void request(int n) {}

                    @Override
                    public void cancel(String message, Throwable cause) {}

                    @Override
                    public void halfClose() {}

                    @Override
                    public void sendMessage(ReqT message) {}
                };
            }

            @Override
            public String authority() {
                return "test";
            }
        };
        MethodDescriptor.Marshaller<byte[]> bytes = new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(byte[] value) {
                return new ByteArrayInputStream(value);
            }

            @Override
            public byte[] parse(InputStream stream) {
                return new byte[0];
            }
        };
        MethodDescriptor<byte[], byte[]> method = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("t.Svc/M")
                .setRequestMarshaller(bytes)
                .setResponseMarshaller(bytes)
                .build();
        GrpcCallContext.INTERCEPTOR
                .interceptCall(method, CallOptions.DEFAULT.withOption(ResponseMetadataCapture.KEY, capture), channel)
                .start(new ClientCall.Listener<>() {}, new Metadata());
        return capture;
    }

    @Test
    @DisplayName(
            "GIVEN a call through the channel interceptor WHEN the backend answers SHOULD capture its headers and trailers")
    void given_interceptedCall_when_answered_should_captureHeadersAndTrailers() {
        ResponseMetadataCapture capture = captured(metadata("x-version", "7"), metadata("x-cost", "3"));

        assertThat(capture.headers().get(Metadata.Key.of("x-version", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("7");
        assertThat(capture.trailers().get(Metadata.Key.of("x-cost", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("3");
    }

    @Test
    @DisplayName(
            "GIVEN an allowlist WHEN applied SHOULD copy only the listed headers and trailers, matching case-insensitively")
    void given_allowlist_when_applied_should_copyOnlyListedNames() {
        var forwarder = ResponseHeaderForwarder.of(List.of("X-Version", "x-cost"));
        HttpHeaders out = new HttpHeaders();

        forwarder.apply(captured(metadata("x-version", "7", "x-secret", "s"), metadata("x-cost", "3")), out, true);

        assertThat(out.getFirst("x-version")).isEqualTo("7");
        assertThat(out.getFirst("x-cost")).isEqualTo("3");
        assertThat(out.containsKey("x-secret")).isFalse();
    }

    @Test
    @DisplayName("GIVEN * WHEN applied SHOULD copy every name except gRPC, binary, framing and hop-by-hop ones")
    void given_wildcard_when_applied_should_skipReservedNames() {
        var forwarder = ResponseHeaderForwarder.of(List.of("*"));
        HttpHeaders out = new HttpHeaders();
        Metadata headers =
                metadata("x-version", "7", "grpc-accept-encoding", "gzip", "content-type", "application/grpc");
        headers.put(Metadata.Key.of("x-trace-bin", Metadata.BINARY_BYTE_MARSHALLER), new byte[] {1});

        forwarder.apply(captured(headers, metadata("grpc-status", "0", "connection", "close")), out, true);

        assertThat(out.toSingleValueMap()).containsOnlyKeys("x-version");
    }

    @Test
    @DisplayName(
            "GIVEN a header the gateway already set WHEN the backend sends the same name SHOULD keep the gateway's value")
    void given_gatewaySetHeader_when_backendSendsSameName_should_notReplaceIt() {
        var forwarder = ResponseHeaderForwarder.of(List.of("*"));
        HttpHeaders out = new HttpHeaders();
        out.set("X-Request-Id", "gateway-id");

        forwarder.apply(captured(metadata("x-request-id", "backend-id"), null), out, true);

        assertThat(out.get("X-Request-Id")).containsExactly("gateway-id");
    }

    @Test
    @DisplayName("GIVEN a name in both headers and trailers WHEN applied SHOULD send both values")
    void given_nameInHeadersAndTrailers_when_applied_should_sendBoth() {
        var forwarder = ResponseHeaderForwarder.of(List.of("x-step"));
        HttpHeaders out = new HttpHeaders();

        forwarder.apply(captured(metadata("x-step", "start"), metadata("x-step", "end")), out, true);

        assertThat(out.get("x-step")).containsExactly("start", "end");
    }

    @Test
    @DisplayName("GIVEN a streamed response WHEN applied without trailers SHOULD copy the headers only")
    void given_withoutTrailers_when_applied_should_copyHeadersOnly() {
        HttpHeaders out = new HttpHeaders();

        ResponseHeaderForwarder.of(List.of("*"))
                .apply(captured(metadata("x-version", "7"), metadata("x-cost", "3")), out, false);

        assertThat(out.toSingleValueMap()).containsOnlyKeys("x-version");
    }

    @Test
    @DisplayName("GIVEN no names or only blanks WHEN a forwarder is built SHOULD build none")
    void given_noNames_when_of_should_returnNull() {
        assertThat(ResponseHeaderForwarder.of(List.of())).isNull();
        assertThat(ResponseHeaderForwarder.of(null)).isNull();
        assertThat(ResponseHeaderForwarder.of(List.of(" "))).isNull();
    }

    @Test
    @DisplayName("GIVEN a capture before any response WHEN applied SHOULD copy nothing")
    void given_emptyCapture_when_applied_should_copyNothing() {
        HttpHeaders out = new HttpHeaders();

        ResponseHeaderForwarder.of(List.of("*")).apply(new ResponseMetadataCapture(), out, true);

        assertThat(out.isEmpty()).isTrue();
    }
}
