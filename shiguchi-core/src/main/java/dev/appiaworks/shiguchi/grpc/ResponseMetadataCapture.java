package dev.appiaworks.shiguchi.grpc;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the response headers and trailers a backend sends on one call, so the gateway can copy
 * chosen ones onto the HTTP response.
 *
 * <p>Attach one to a call with {@code callOptions.withOption(ResponseMetadataCapture.KEY, capture)};
 * {@link GrpcCallContext#INTERCEPTOR}, already on every gateway channel, fills it in. The
 * interceptor sits above the channel's retry and hedging logic, so what it sees is the attempt
 * that was committed, not a discarded one.
 */
public final class ResponseMetadataCapture {

    public static final CallOptions.Key<ResponseMetadataCapture> KEY =
            CallOptions.Key.createWithDefault("response-metadata-capture", null);

    private volatile Metadata headers;
    private volatile Metadata trailers;

    /** The backend's response headers, or null when none have arrived. */
    public Metadata headers() {
        return headers;
    }

    /** The backend's trailers, or null while the call is still open. */
    public Metadata trailers() {
        return trailers;
    }

    /**
     * @param holdResponse whether the call answers with one message. Its caller typically writes
     *     the HTTP response as soon as that message arrives, before the trailers do; holding the
     *     message until the call closes means the trailers are recorded by the time it is written.
     */
    <RespT> ClientCall.Listener<RespT> wrap(ClientCall.Listener<RespT> listener, boolean holdResponse) {
        return new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
            private final List<RespT> held = new ArrayList<>(1);

            @Override
            public void onHeaders(Metadata received) {
                headers = received;
                super.onHeaders(received);
            }

            @Override
            public void onMessage(RespT message) {
                if (holdResponse) held.add(message);
                else super.onMessage(message);
            }

            @Override
            public void onClose(Status status, Metadata received) {
                trailers = received;
                for (RespT message : held) super.onMessage(message);
                held.clear();
                super.onClose(status, received);
            }
        };
    }
}
