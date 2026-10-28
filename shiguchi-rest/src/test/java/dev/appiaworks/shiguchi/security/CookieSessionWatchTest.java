package dev.appiaworks.shiguchi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("Cookie session watch")
class CookieSessionWatchTest {

    private static final Duration POLL = Duration.ofMillis(50);

    private final Map<String, Boolean> active = new ConcurrentHashMap<>();
    private final AtomicBoolean storeDown = new AtomicBoolean();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final CookieSessions sessions = handle -> storeDown.get()
            ? Mono.error(new IllegalStateException("store down"))
            : Mono.just(active.getOrDefault(handle, true));
    private final CookieSessionWatch watch = new CookieSessionWatch(sessions, POLL, failures::add);

    private static Authentication cookieCaller(String handle) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "n/a");
        auth.setDetails(new CookieAuthenticated(null, handle, "a"));
        return auth;
    }

    private static Authentication bearerCaller() {
        return new TestingAuthenticationToken("alice", "n/a");
    }

    @Test
    @DisplayName("GIVEN a cookie caller WHEN asked if it is bound SHOULD say yes only with a sessions bean")
    void given_cookie_caller_when_asking_bound_should_depend_on_sessions_bean() {
        assertThat(watch.bound(cookieCaller("h1"))).isTrue();
        assertThat(watch.bound(bearerCaller())).isFalse();
        assertThat(watch.bound(null)).isFalse();
        assertThat(new CookieSessionWatch(null, POLL, null).bound(cookieCaller("h1")))
                .isFalse();
    }

    @Test
    @DisplayName("GIVEN a bearer caller WHEN watching for the end SHOULD never complete")
    void given_bearer_caller_when_watching_should_never_end() {
        StepVerifier.create(watch.ended(bearerCaller()).timeout(Duration.ofMillis(300)))
                .expectError(TimeoutException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("GIVEN a bound caller WHEN this instance ends its session SHOULD complete at once, before any poll")
    void given_bound_caller_when_session_ended_event_should_complete_at_once() {
        CookieSessionWatch slow = new CookieSessionWatch(sessions, Duration.ofHours(1), null);
        AtomicBoolean ended = new AtomicBoolean();
        slow.ended(cookieCaller("h1")).subscribe(v -> {}, e -> {}, () -> ended.set(true));

        slow.onSessionEnded(new CookieSessionEnded("other"));
        assertThat(ended).isFalse();
        slow.onSessionEnded(new CookieSessionEnded("h1"));

        assertThat(ended).isTrue();
    }

    @Test
    @DisplayName("GIVEN a bound caller WHEN the store reports its session gone SHOULD complete by the poll")
    void given_bound_caller_when_store_reports_session_gone_should_complete_by_poll() {
        active.put("h1", false);

        StepVerifier.create(watch.ended(cookieCaller("h1"))).expectComplete().verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName(
            "GIVEN a bound caller WHEN the store cannot be asked SHOULD keep the stream and report each failed poll")
    void given_bound_caller_when_store_errors_should_keep_stream_and_report() throws Exception {
        storeDown.set(true);
        AtomicBoolean ended = new AtomicBoolean();
        var subscription = watch.ended(cookieCaller("h1")).subscribe(v -> {}, e -> {}, () -> ended.set(true));

        Thread.sleep(300);

        assertThat(ended).isFalse();
        assertThat(failures).isNotEmpty();
        subscription.dispose();
    }

    @Test
    @DisplayName(
            "GIVEN a bound caller WHEN asking if its session is active SHOULD answer from the store, and yes if it cannot")
    void given_bound_caller_when_asking_active_should_answer_from_store() {
        active.put("gone", false);

        assertThat(watch.active(cookieCaller("gone")).block()).isFalse();
        assertThat(watch.active(cookieCaller("alive")).block()).isTrue();
        assertThat(watch.active(bearerCaller()).block()).isTrue();
        storeDown.set(true);
        assertThat(watch.active(cookieCaller("gone")).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a watch that was cancelled WHEN time passes SHOULD stop polling the store")
    void given_cancelled_watch_when_time_passes_should_stop_polling() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        CookieSessionWatch counting =
                new CookieSessionWatch(handle -> Mono.fromSupplier(() -> polls.incrementAndGet() > 0), POLL, null);
        var subscription = counting.ended(cookieCaller("h1")).subscribe();
        Thread.sleep(250);

        subscription.dispose();
        int atCancel = polls.get();
        Thread.sleep(250);

        assertThat(atCancel).isPositive();
        assertThat(polls.get()).isLessThanOrEqualTo(atCancel + 1);
    }
}
