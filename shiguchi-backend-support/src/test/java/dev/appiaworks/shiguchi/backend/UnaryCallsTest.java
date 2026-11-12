package dev.appiaworks.shiguchi.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UnaryCalls")
class UnaryCallsTest {

    private static final class Recorder implements StreamObserver<String> {
        final List<String> values = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override
        public void onNext(String value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }

    @Test
    @DisplayName("GIVEN a body that returns WHEN responded SHOULD send the value and complete")
    void given_value_when_respond_should_sendAndComplete() {
        Recorder out = new Recorder();

        UnaryCalls.respond(out, () -> "ok");

        assertThat(out.values).containsExactly("ok");
        assertThat(out.completed).isTrue();
        assertThat(out.error).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a body that rejects the request WHEN responded SHOULD fail the call with that status, not UNKNOWN")
    void given_invalidArgument_when_respond_should_failWithThatStatus() {
        Recorder out = new Recorder();

        UnaryCalls.respond(out, () -> {
            throw Names.invalid("Unsortable field: x");
        });

        assertThat(Status.fromThrowable(out.error).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(out.values).isEmpty();
        assertThat(out.completed).isFalse();
    }

    @Test
    @DisplayName("GIVEN a body with a bug WHEN responded SHOULD let the exception escape rather than hide it")
    void given_bug_when_respond_should_propagate() {
        assertThatThrownBy(() -> UnaryCalls.respond(new Recorder(), () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);
    }
}
