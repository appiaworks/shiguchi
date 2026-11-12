package dev.appiaworks.shiguchi.backend;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.function.Supplier;

/**
 * Answers a unary gRPC call from a plain method body, turning the helpers' client errors into the
 * call's status.
 *
 * <p>This matters: grpc-java reports an exception that escapes a handler as {@code UNKNOWN}, even
 * a {@code StatusRuntimeException} carrying {@code INVALID_ARGUMENT}. Only
 * {@code observer.onError(...)} sets the status the caller sees, and the gateway maps that status
 * to HTTP (a 400 for {@code INVALID_ARGUMENT}, not a 500). So a bad sort key or a forged page
 * token thrown by {@link SortSpec} or {@link PageCursor} must go through {@code onError}:
 *
 * <pre>{@code
 * public void listBooks(ListBooksRequest request, StreamObserver<ListBooksResponse> out) {
 *     UnaryCalls.respond(out, () -> {
 *         ...
 *         return ListBooksResponse.newBuilder()....build();
 *     });
 * }
 * }</pre>
 *
 * <p>Any other exception is not caught: it is a bug in the backend, and grpc-java reports it as
 * {@code UNKNOWN} without leaking details to the caller.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.21.0")
public final class UnaryCalls {

    private UnaryCalls() {}

    /** Completes {@code observer} with {@code body}'s result, or fails it with a status exception's status. */
    public static <T> void respond(StreamObserver<T> observer, Supplier<T> body) {
        T response;
        try {
            response = body.get();
        } catch (StatusRuntimeException e) {
            observer.onError(e);
            return;
        }
        observer.onNext(response);
        observer.onCompleted();
    }
}
