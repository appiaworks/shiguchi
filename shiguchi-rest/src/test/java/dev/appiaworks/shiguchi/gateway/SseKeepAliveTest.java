package dev.appiaworks.shiguchi.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * BDD tests for {@link SseKeepAlive#withKeepAlive}: idle-ping interleaving, timer reset on
 * frames, pass-through of completion/errors, the disabled (zero interval) path, and cancellation
 * propagation to the frames source.
 *
 * <p>Time is driven explicitly through a {@link VirtualTimeScheduler} instance passed as the
 * {@code scheduler} argument (rather than {@code StepVerifier.withVirtualTime()}), because frames
 * must be emitted by hand between time advances to exercise the timer-reset behavior.
 */
@DisplayName("SseKeepAlive")
class SseKeepAliveTest {

    @Test
    @DisplayName(
            "GIVEN a silent source WHEN 15s elapse with no frame SHOULD emit a ping, and WHEN 30s elapse SHOULD have emitted two pings")
    void given_silentSource_when_silenceExceedsInterval_should_emitPings() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();

        var result = SseKeepAlive.withKeepAlive(sink.asFlux(), Duration.ofSeconds(15), () -> "ping", vts);

        StepVerifier.create(result)
                .then(() -> vts.advanceTimeBy(Duration.ofSeconds(15)))
                .expectNext("ping")
                .then(() -> vts.advanceTimeBy(Duration.ofSeconds(15)))
                .expectNext("ping")
                .then(sink::tryEmitComplete)
                .verifyComplete();
    }

    @Test
    @DisplayName(
            "GIVEN a frame arrives 10s in WHEN no further event occurs by 20s SHOULD not ping yet, and WHEN 25s is reached SHOULD ping 15s after that frame")
    void given_frameResetsTimer_when_intervalElapsesAfterFrame_should_pingRelativeToFrame() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();

        var result = SseKeepAlive.withKeepAlive(sink.asFlux(), Duration.ofSeconds(15), () -> "ping", vts);

        StepVerifier.create(result)
                .then(() -> vts.advanceTimeBy(Duration.ofSeconds(10)))
                .then(() -> sink.tryEmitNext("frame"))
                .expectNext("frame")
                // 20s total elapsed, but only 10s since the frame reset the timer: no ping yet.
                .then(() -> vts.advanceTimeBy(Duration.ofSeconds(10)))
                .expectNoEvent(Duration.ZERO)
                // 25s total elapsed, 15s since the frame: the ping is now due.
                .then(() -> vts.advanceTimeBy(Duration.ofSeconds(5)))
                .expectNext("ping")
                .then(sink::tryEmitComplete)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN frames completes WHEN no ping is due SHOULD complete the output without emitting further pings")
    void given_framesCompletes_when_noPingDue_should_completeOutput() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();

        var result = SseKeepAlive.withKeepAlive(sink.asFlux(), Duration.ofSeconds(15), () -> "ping", vts);

        StepVerifier.create(result)
                .then(() -> sink.tryEmitNext("frame"))
                .expectNext("frame")
                .then(sink::tryEmitComplete)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN frames errors WHEN the failure propagates SHOULD fail the output with the same exception")
    void given_framesErrors_when_failurePropagates_should_failOutputWithSameException() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();
        IllegalStateException failure = new IllegalStateException("upstream failed");

        var result = SseKeepAlive.withKeepAlive(sink.asFlux(), Duration.ofSeconds(15), () -> "ping", vts);

        StepVerifier.create(result)
                .then(() -> sink.tryEmitError(failure))
                .verifyErrorSatisfies(error -> assertThat(error).isSameAs(failure));
    }

    @Test
    @DisplayName(
            "GIVEN a zero keepalive interval WHEN frames are emitted SHOULD pass them through unchanged with no pings")
    void given_zeroInterval_when_framesEmitted_should_passThroughUnchangedWithNoPings() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();

        var result = SseKeepAlive.withKeepAlive(sink.asFlux(), Duration.ZERO, () -> "ping", vts);

        StepVerifier.create(result)
                .then(() -> sink.tryEmitNext("frame-1"))
                .expectNext("frame-1")
                .then(() -> vts.advanceTimeBy(Duration.ofHours(1)))
                .then(() -> sink.tryEmitNext("frame-2"))
                .expectNext("frame-2")
                .then(sink::tryEmitComplete)
                .verifyComplete();
    }

    @Test
    @DisplayName("GIVEN a subscriber WHEN the output is cancelled SHOULD cancel the underlying frames source")
    void given_subscriber_when_outputCancelled_should_cancelFramesSource() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();

        var result = SseKeepAlive.withKeepAlive(sink.asFlux(), Duration.ofSeconds(15), () -> "ping", vts);

        StepVerifier.create(result)
                .then(() -> assertThat(sink.currentSubscriberCount()).isEqualTo(1))
                .thenCancel()
                .verify();

        assertThat(sink.currentSubscriberCount()).isEqualTo(0);
    }

    @Test
    @DisplayName(
            "GIVEN a slow reader and a stream that sends two items then fails WHEN the reader catches up SHOULD deliver both items before the error")
    void given_slowReaderAndItemsThenError_when_readerCatchesUp_should_deliverItemsBeforeError() {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        VirtualTimeScheduler vts = VirtualTimeScheduler.create();
        IllegalStateException failure = new IllegalStateException("stream broke");

        // The backend has already sent everything, error included, before the caller reads.
        sink.tryEmitNext("a");
        sink.tryEmitNext("b");
        sink.tryEmitError(failure);

        StepVerifier.create(SseKeepAlive.withKeepAlive(sink.asFlux(), Duration.ofSeconds(15), () -> "ping", vts), 0)
                .thenRequest(10)
                .expectNext("a", "b")
                .expectErrorMatches(e -> e == failure)
                .verify(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName(
            "GIVEN items then an error arriving on another thread WHEN a slow reader drains them SHOULD never lose an item to the error")
    void given_itemsThenErrorOnAnotherThread_when_slowReaderDrains_should_neverLoseAnItem() {
        IllegalStateException failure = new IllegalStateException("stream broke");
        int lost = 0;
        for (int run = 0; run < 2000; run++) {
            reactor.core.publisher.Flux<String> frames = reactor.core.publisher.Flux.just("a", "b", "c")
                    .concatWith(reactor.core.publisher.Flux.error(failure))
                    .subscribeOn(reactor.core.scheduler.Schedulers.parallel());
            java.util.List<String> got = new java.util.concurrent.CopyOnWriteArrayList<>();
            try {
                SseKeepAlive.withKeepAlive(
                                frames,
                                Duration.ofSeconds(15),
                                () -> "ping",
                                reactor.core.scheduler.Schedulers.parallel())
                        .publishOn(reactor.core.scheduler.Schedulers.single(), 1)
                        .doOnNext(got::add)
                        .blockLast(Duration.ofSeconds(30));
            } catch (IllegalStateException expected) {
                // the stream's own failure, after its items
            }
            if (got.size() != 3) lost++;
        }
        assertThat(lost).as("runs that lost an item to the error").isZero();
    }
}
