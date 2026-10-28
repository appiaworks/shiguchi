package dev.appiaworks.shiguchi.bff.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests move by hand. */
final class TestClock extends Clock {

    private final AtomicReference<Instant> now;

    TestClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
