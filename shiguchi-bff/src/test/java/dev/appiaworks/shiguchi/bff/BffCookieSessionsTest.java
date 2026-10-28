package dev.appiaworks.shiguchi.bff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.appiaworks.shiguchi.bff.BffTestFixtures.MutableClock;
import dev.appiaworks.shiguchi.bff.session.BffSessionKeys;
import dev.appiaworks.shiguchi.bff.session.MemoryBffSessionStore;
import dev.appiaworks.shiguchi.security.CookieSessionEnded;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BFF cookie sessions and session-ended events")
class BffCookieSessionsTest {

    private static final Duration HUNDRED_DAYS = Duration.ofDays(100);

    private MutableClock clock;
    private MemoryBffSessionStore memory;
    private BffProperties properties;
    private BffCookieSessions sessions;
    private final List<CookieSessionEnded> ended = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BffTestFixtures.T0);
        memory = new MemoryBffSessionStore(clock);
        properties = BffTestFixtures.properties();
        sessions = new BffCookieSessions(memory, properties, clock);
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private String seed(Instant created, Instant lastSeen) {
        String cookie = BffSessionKeys.newCookieValue();
        memory.save(
                        BffTestFixtures.session(cookie, created, lastSeen, created.plus(Duration.ofMinutes(5))),
                        HUNDRED_DAYS)
                .block();
        return BffSessionKeys.key(cookie);
    }

    @Test
    @DisplayName("GIVEN a stored live session WHEN asked about its handle SHOULD be active")
    void given_live_session_when_asked_should_be_active() {
        String handle = seed(clock.instant(), clock.instant());

        assertThat(sessions.active(handle).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN an unknown or null handle WHEN asked SHOULD be inactive")
    void given_unknown_handle_when_asked_should_be_inactive() {
        assertThat(sessions.active("nope").block()).isFalse();
        assertThat(sessions.active(null).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a session past its absolute timeout WHEN asked SHOULD be inactive")
    void given_session_past_absolute_timeout_when_asked_should_be_inactive() {
        String handle = seed(clock.instant(), clock.instant());
        clock.advance(properties.getSession().getAbsoluteTimeout().plusSeconds(1));

        assertThat(sessions.active(handle).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a session idle past its timeout WHEN asked SHOULD be inactive")
    void given_session_idle_past_timeout_when_asked_should_be_inactive() {
        String handle = seed(clock.instant(), clock.instant());
        clock.advance(properties.getSession().getIdleTimeout().plusSeconds(1));

        assertThat(sessions.active(handle).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a session WHEN asked SHOULD not touch it, so a socket never keeps an idle session alive")
    void given_session_when_asked_should_not_touch() {
        Instant seen = clock.instant();
        String handle = seed(seen, seen);
        clock.advance(Duration.ofMinutes(10));

        sessions.active(handle).block();

        assertThat(memory.find(handle).block().lastSeen()).isEqualTo(seen);
    }

    @Test
    @DisplayName("GIVEN the ending store WHEN a session is deleted SHOULD publish CookieSessionEnded with its handle")
    void given_ending_store_when_session_deleted_should_publish_event() {
        String handle = seed(clock.instant(), clock.instant());
        BffEndedSessionStore store = new BffEndedSessionStore(memory, ended::add);

        store.delete(handle).block();

        assertThat(ended).containsExactly(new CookieSessionEnded(handle));
        assertThat(sessions.active(handle).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN the ending store WHEN a session is only read, saved or touched SHOULD publish nothing")
    void given_ending_store_when_session_not_deleted_should_publish_nothing() {
        BffEndedSessionStore store = new BffEndedSessionStore(memory, ended::add);
        String handle = seed(clock.instant(), clock.instant());

        store.find(handle).block();
        store.touch(handle, clock.instant(), HUNDRED_DAYS).block();

        assertThat(ended).isEmpty();
    }
}
