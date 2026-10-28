package dev.appiaworks.shiguchi.cursus;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * The request metadata of a call on the hosted {@code PushService}, kept in the gRPC {@link
 * Context} for the service to read, and the caller it names: a bearer token in {@code
 * authorization}, decoded by the gateway's JWT decoder. Nothing else identifies a caller: the
 * {@code x-principal} the gateway adds for backends is not trusted here, because the native gRPC
 * port forwards a caller's own metadata verbatim.
 */
final class PushGrpcHeaders implements ServerInterceptor {

    static final Context.Key<Metadata> METADATA = Context.key("push-metadata");
    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return Contexts.interceptCall(Context.current().withValue(METADATA, headers), call, headers, next);
    }

    /**
     * The caller of the current call: empty when it sent no bearer token, the decoded
     * authentication when it did, or {@link PushException#unauthenticated} when the token is
     * rejected or no decoder is configured. Reads the metadata synchronously, on the call's thread.
     */
    static Mono<Optional<Authentication>> caller(PushTokenAuthenticator tokens) {
        Metadata headers = METADATA.get();
        String token = headers == null ? null : bearer(headers.get(AUTHORIZATION));
        if (token == null) return Mono.just(Optional.empty());
        return tokens.authenticate(token).map(Optional::of);
    }

    /** The {@code x-push-device} metadata of the current call, or null. */
    static String device() {
        Metadata headers = METADATA.get();
        return headers == null ? null : headers.get(DEVICE);
    }

    static final Metadata.Key<String> DEVICE = Metadata.Key.of("x-push-device", Metadata.ASCII_STRING_MARSHALLER);

    static String bearer(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = h.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
