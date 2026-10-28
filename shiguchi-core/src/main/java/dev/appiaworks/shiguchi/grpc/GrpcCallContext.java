package dev.appiaworks.shiguchi.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Per-call metadata propagation without per-request channel wrapping.
 *
 * Usage:
 *   CallOptions opts = CallOptions.DEFAULT.withOption(GrpcCallContext.METADATA_KEY, metadata);
 *   channel.newCall(grpcMethod, opts);
 *
 * Install INTERCEPTOR once at channel build time. It reads metadata from CallOptions
 * and merges it into the outbound headers — no ClientInterceptors.intercept() per request.
 * A {@link ResponseMetadataCapture} in the same CallOptions is filled with the backend's
 * response headers and trailers.
 */
public final class GrpcCallContext {

    public static final CallOptions.Key<Metadata> METADATA_KEY =
            CallOptions.Key.createWithDefault("request-metadata", null);

    public static final ClientInterceptor INTERCEPTOR = new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

            Metadata extra = callOptions.getOption(METADATA_KEY);
            ResponseMetadataCapture capture = callOptions.getOption(ResponseMetadataCapture.KEY);
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);

            boolean addHeaders = extra != null && !extra.keys().isEmpty();
            if (!addHeaders && capture == null) return call;

            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    if (addHeaders) headers.merge(extra);
                    super.start(
                            capture != null
                                    ? capture.wrap(
                                            responseListener, method.getType().serverSendsOneMessage())
                                    : responseListener,
                            headers);
                }
            };
        }
    };

    private GrpcCallContext() {}
}
