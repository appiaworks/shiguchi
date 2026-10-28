package dev.appiaworks.shiguchi.bff.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemoryBffSessionStoreTest extends BffSessionStoreContract {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
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
    };
    private MemoryBffSessionStore store;

    @Override
    BffSessionStore store() {
        store = new MemoryBffSessionStore(clock);
        return store;
    }

    @Override
    void advance(Duration d) {
        now.updateAndGet(i -> i.plus(d));
    }

    @AfterEach
    void close() {
        if (store != null) store.close();
    }

    @Test
    @DisplayName("GIVEN expired entries WHEN swept SHOULD keep live ones")
    void given_expired_entries_when_swept_should_keep_live_ones() {
        BffSessionStore s = store();
        s.save(session("old"), Duration.ofSeconds(10)).block();
        s.save(session("new"), LONG).block();
        advance(Duration.ofSeconds(11));
        store.sweep();

        assertThat(s.find("old").blockOptional()).isEmpty();
        assertThat(s.find("new").blockOptional()).isPresent();
    }
}
