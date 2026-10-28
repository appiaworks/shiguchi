package dev.appiaworks.shiguchi.bff.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Behaviour every {@link BffSessionStore} must have. */
abstract class BffSessionStoreContract {

    static final Duration LONG = Duration.ofMinutes(30);

    /** @return a fresh, empty store */
    abstract BffSessionStore store();

    /** Moves the store's notion of time forward; stores on the real clock sleep. */
    abstract void advance(Duration d);

    /** The short time to live the expiry cases use; stores on the real clock return a few seconds. */
    Duration ttl() {
        return Duration.ofSeconds(10);
    }

    static BffSession session(String key) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new BffSession(
                key,
                "sub-1",
                "google",
                "web",
                "sid-1",
                "Ann",
                "ann@example.com",
                null,
                "at",
                now.plusSeconds(600),
                "rt",
                "idt",
                now,
                now,
                1L);
    }

    static BffLogin login(String state) {
        return new BffLogin(
                state,
                "verifier",
                "nonce",
                "google",
                "web",
                "https://app/",
                "https://gw/bff/callback/google",
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("GIVEN a saved session WHEN found SHOULD return it unchanged")
    void given_saved_session_when_found_should_return_it() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();

        assertThat(store.find("k").block()).isEqualTo(session("k"));
    }

    @Test
    @DisplayName("GIVEN no session WHEN found SHOULD be empty")
    void given_no_session_when_found_should_be_empty() {
        assertThat(store().find("missing").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a session past its ttl WHEN found SHOULD be empty")
    void given_expired_session_when_found_should_be_empty() {
        BffSessionStore store = store();
        store.save(session("k"), ttl()).block();
        advance(ttl().plusSeconds(1));

        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a session WHEN touched SHOULD extend the ttl and update lastSeen")
    void given_session_when_touched_should_extend_ttl_and_update_last_seen() {
        BffSessionStore store = store();
        Duration sixTenths = ttl().multipliedBy(6).dividedBy(10);
        store.save(session("k"), ttl()).block();
        advance(sixTenths);
        Instant seen = Instant.parse("2026-01-01T01:00:00Z");
        store.touch("k", seen, ttl()).block();
        advance(sixTenths);

        BffSession found = store.find("k").block();
        assertThat(found).isNotNull();
        assertThat(found.lastSeen()).isEqualTo(seen);
    }

    @Test
    @DisplayName("GIVEN a missing session WHEN touched SHOULD not create it")
    void given_missing_session_when_touched_should_not_create_it() {
        BffSessionStore store = store();
        store.touch("k", Instant.now(), LONG).block();

        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a session WHEN deleted SHOULD be gone, and deleting again is fine")
    void given_session_when_deleted_should_be_gone() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();
        store.delete("k").block();
        store.delete("k").block();

        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a saved login WHEN taken twice SHOULD return it once")
    void given_saved_login_when_taken_twice_should_return_once() {
        BffSessionStore store = store();
        store.saveLogin(login("st"), LONG).block();

        assertThat(store.takeLogin("st").block()).isEqualTo(login("st"));
        assertThat(store.takeLogin("st").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a login past its ttl WHEN taken SHOULD be empty")
    void given_expired_login_when_taken_should_be_empty() {
        BffSessionStore store = store();
        store.saveLogin(login("st"), ttl()).block();
        advance(ttl().plusSeconds(1));

        assertThat(store.takeLogin("st").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a deleted session WHEN touched SHOULD not bring it back")
    void given_deleted_session_when_touched_should_not_resurrect_it() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();
        store.delete("k").block();
        store.touch("k", Instant.parse("2026-01-01T01:00:00Z"), LONG).block();

        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a session past its ttl WHEN touched SHOULD not bring it back")
    void given_expired_session_when_touched_should_not_resurrect_it() {
        BffSessionStore store = store();
        store.save(session("k"), ttl()).block();
        advance(ttl().plusSeconds(1));
        store.touch("k", Instant.parse("2026-01-01T01:00:00Z"), LONG).block();

        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a session with every optional field absent WHEN found SHOULD round-trip the nulls")
    void given_session_without_optional_fields_when_found_should_round_trip_nulls() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        BffSession bare = new BffSession(
                "k", "sub-1", "google", "web", null, null, null, null, "at", null, null, null, now, now, 0L);
        BffSessionStore store = store();
        store.save(bare, LONG).block();

        assertThat(store.find("k").block()).isEqualTo(bare);
    }

    @Test
    @DisplayName("GIVEN a session with every field set WHEN found SHOULD round-trip each one, long tokens included")
    void given_session_with_every_field_when_found_should_round_trip_each() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        String longToken = "eyJ" + "a".repeat(6000) + ".b=c/d+e";
        BffSession full = new BffSession(
                "k",
                "sub-1",
                "google",
                "web",
                "sid-1",
                "Ann Ünï \"quoted\" \n second line",
                "ann@example.com",
                "https://pics.example.com/a.png?x=1&y=2",
                longToken,
                now.plusSeconds(600),
                "rt",
                longToken + "id",
                now,
                now.plusSeconds(5),
                7L);
        BffSessionStore store = store();
        store.save(full, LONG).block();

        assertThat(store.find("k").block()).isEqualTo(full);
    }

    @Test
    @DisplayName("GIVEN a session with acr, amr and auth_time WHEN found SHOULD round-trip the authentication context")
    void given_session_with_authentication_context_when_found_should_round_trip_it() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        BffSession withContext = new BffSession(
                "k",
                "sub-1",
                "google",
                "web",
                null,
                null,
                null,
                null,
                "at",
                null,
                null,
                null,
                now,
                now,
                0L,
                "urn:example:mfa",
                List.of("pwd", "otp"),
                now.minusSeconds(90));
        BffSessionStore store = store();
        store.save(withContext, LONG).block();

        BffSession found = store.find("k").block();
        assertThat(found).isEqualTo(withContext);
        assertThat(found.acr()).isEqualTo("urn:example:mfa");
        assertThat(found.amr()).containsExactly("pwd", "otp");
        assertThat(found.authTime()).isEqualTo(now.minusSeconds(90));
    }

    @Test
    @DisplayName("GIVEN a session with claims and a user agent WHEN found SHOULD round-trip both")
    void given_session_with_claims_and_user_agent_when_found_should_round_trip_them() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        java.util.Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("roles", List.of("admin", "dev"));
        claims.put("tenant", "acme");
        claims.put("groups", java.util.Map.of("team", "core"));
        BffSession withClaims = new BffSession(
                "k",
                "sub-1",
                "google",
                "web",
                null,
                null,
                null,
                null,
                "at",
                null,
                null,
                null,
                now,
                now,
                0L,
                null,
                List.of(),
                null,
                claims,
                "Mozilla/5.0 (X11)");
        BffSessionStore store = store();
        store.save(withClaims, LONG).block();

        BffSession found = store.find("k").block();
        assertThat(found.claims()).containsEntry("tenant", "acme").containsEntry("roles", List.of("admin", "dev"));
        assertThat(found.claims().get("groups")).isEqualTo(java.util.Map.of("team", "core"));
        assertThat(found.userAgent()).isEqualTo("Mozilla/5.0 (X11)");
    }

    @Test
    @DisplayName("GIVEN a session without claims WHEN found SHOULD have none and no user agent")
    void given_session_without_claims_when_found_should_have_none() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();

        BffSession found = store.find("k").block();
        assertThat(found.claims()).isEmpty();
        assertThat(found.userAgent()).isNull();
    }

    @Test
    @DisplayName(
            "GIVEN a session without authentication context WHEN found SHOULD have no acr, no amr and no auth_time")
    void given_session_without_authentication_context_when_found_should_have_none() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();

        BffSession found = store.find("k").block();
        assertThat(found.acr()).isNull();
        assertThat(found.amr()).isEmpty();
        assertThat(found.authTime()).isNull();
    }

    @Test
    @DisplayName("GIVEN a login with max_age WHEN taken SHOULD return it, and one without SHOULD return none")
    void given_login_with_max_age_when_taken_should_return_it() {
        BffSessionStore store = store();
        BffLogin plain = login("plain");
        BffLogin aged = new BffLogin(
                "aged",
                plain.codeVerifier(),
                plain.nonce(),
                plain.provider(),
                plain.application(),
                plain.returnTo(),
                plain.redirectUri(),
                plain.created(),
                300L);
        store.saveLogin(plain, LONG).block();
        store.saveLogin(aged, LONG).block();

        assertThat(store.takeLogin("plain").block().maxAge()).isNull();
        assertThat(store.takeLogin("aged").block()).isEqualTo(aged);
    }

    @Test
    @DisplayName("GIVEN a session replaced with an authentication context WHEN found SHOULD return the new context")
    void given_session_replaced_with_context_when_found_should_return_new_context() {
        BffSessionStore store = store();
        BffSession before = session("k");
        store.save(before, LONG).block();
        BffSession next = new BffSession(
                "k",
                before.subject(),
                before.provider(),
                before.application(),
                before.sid(),
                before.name(),
                before.email(),
                before.picture(),
                before.accessToken(),
                before.accessTokenExpiresAt(),
                before.refreshToken(),
                before.idToken(),
                before.created(),
                before.lastSeen(),
                before.version() + 1,
                "urn:example:mfa",
                List.of("mfa"),
                before.created());

        assertThat(store.replaceIfVersion(next, before.version()).block()).isTrue();
        assertThat(store.find("k").block()).isEqualTo(next);
    }

    @Test
    @DisplayName("GIVEN a saved session WHEN saved again under the same key SHOULD replace it entirely")
    void given_saved_session_when_saved_again_should_replace_it() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        BffSession bare = new BffSession(
                "k", "sub-2", "idp", "web", null, null, null, null, "at2", null, null, null, now, now, 2L);
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();
        store.save(bare, LONG).block();

        assertThat(store.find("k").block()).isEqualTo(bare);
    }

    @Test
    @DisplayName("GIVEN a saved login WHEN two subscribers take it at once SHOULD give it to exactly one")
    void given_saved_login_when_taken_concurrently_should_give_it_to_one() {
        BffSessionStore store = store();
        for (int i = 0; i < 20; i++) {
            String state = "st" + i;
            store.saveLogin(login(state), LONG).block();

            List<BffLogin> taken = Flux.merge(
                            store.takeLogin(state).subscribeOn(Schedulers.parallel()),
                            store.takeLogin(state).subscribeOn(Schedulers.parallel()))
                    .collectList()
                    .block(Duration.ofSeconds(10));

            assertThat(taken).as("round %s", i).containsExactly(login(state));
        }
    }

    @Test
    @DisplayName("GIVEN a login WHEN its state is the key of a session SHOULD keep the two apart")
    void given_login_and_session_with_same_name_when_read_should_stay_apart() {
        BffSessionStore store = store();
        store.saveLogin(login("same"), LONG).block();
        store.save(session("same"), LONG).block();

        assertThat(store.find("same").block()).isEqualTo(session("same"));
        assertThat(store.takeLogin("same").block()).isEqualTo(login("same"));
        assertThat(store.find("same").blockOptional()).isPresent();
    }

    // ---- refresh lease and compare-and-set --------------------------------------------------

    static BffSession next(BffSession s, String accessToken, long version) {
        return new BffSession(
                s.key(),
                s.subject(),
                s.provider(),
                s.application(),
                s.sid(),
                s.name(),
                s.email(),
                s.picture(),
                accessToken,
                s.accessTokenExpiresAt().plusSeconds(600),
                "rt-" + version,
                s.idToken(),
                s.created(),
                s.lastSeen(),
                version);
    }

    @Test
    @DisplayName("GIVEN a session WHEN two owners ask for its lease SHOULD give it to the first only")
    void given_session_when_two_owners_ask_for_lease_should_give_it_to_first() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();

        assertThat(store.tryLease("k", "a", LONG).block()).isTrue();
        assertThat(store.tryLease("k", "b", LONG).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a held lease WHEN its owner asks again SHOULD refuse: the lease is not re-entrant")
    void given_held_lease_when_owner_asks_again_should_refuse() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();
        store.tryLease("k", "a", LONG).block();

        assertThat(store.tryLease("k", "a", LONG).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a held lease WHEN its owner releases it SHOULD let another owner take it")
    void given_held_lease_when_owner_releases_should_let_another_take_it() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();
        store.tryLease("k", "a", LONG).block();
        store.releaseLease("k", "a").block();

        assertThat(store.tryLease("k", "b", LONG).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a held lease WHEN a non-owner releases it SHOULD change nothing")
    void given_held_lease_when_non_owner_releases_should_change_nothing() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();
        store.tryLease("k", "a", LONG).block();
        store.releaseLease("k", "b").block();
        store.releaseLease("missing", "b").block();

        assertThat(store.tryLease("k", "c", LONG).block()).isFalse();
    }

    @Test
    @DisplayName(
            "GIVEN a lease past its ttl WHEN another owner asks SHOULD get it, and the old owner's release is a no-op")
    void given_expired_lease_when_another_asks_should_get_it() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();
        store.tryLease("k", "a", ttl()).block();
        advance(ttl().plusSeconds(1));

        assertThat(store.tryLease("k", "b", LONG).block()).isTrue();
        store.releaseLease("k", "a").block();
        assertThat(store.tryLease("k", "c", LONG).block()).isFalse();
    }

    @Test
    @DisplayName("GIVEN a session WHEN many owners ask for its lease at once SHOULD give it to exactly one")
    void given_session_when_many_ask_for_lease_at_once_should_give_it_to_one() {
        BffSessionStore store = store();
        store.save(session("k"), LONG).block();

        List<Boolean> results = Flux.range(0, 20)
                .flatMap(i -> store.tryLease("k", "o" + i, LONG).subscribeOn(Schedulers.parallel()))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(results).hasSize(20).filteredOn(r -> r).hasSize(1);
    }

    @Test
    @DisplayName("GIVEN a stored version WHEN replaced with that version expected SHOULD store the new session")
    void given_stored_version_when_replaced_with_it_expected_should_store() {
        BffSessionStore store = store();
        BffSession v1 = session("k");
        store.save(v1, LONG).block();
        BffSession v2 = next(v1, "at-2", 2L);

        assertThat(store.replaceIfVersion(v2, 1L).block()).isTrue();
        assertThat(store.find("k").block()).isEqualTo(v2);
    }

    @Test
    @DisplayName(
            "GIVEN a replaced session WHEN replaced again with the stale version SHOULD refuse and keep the stored one")
    void given_stale_version_when_replaced_should_refuse() {
        BffSessionStore store = store();
        BffSession v1 = session("k");
        store.save(v1, LONG).block();
        BffSession v2 = next(v1, "at-2", 2L);
        store.replaceIfVersion(v2, 1L).block();

        assertThat(store.replaceIfVersion(next(v1, "at-other", 2L), 1L).block()).isFalse();
        assertThat(store.find("k").block()).isEqualTo(v2);
    }

    @Test
    @DisplayName("GIVEN no session, or an expired one WHEN replaced SHOULD refuse and create nothing")
    void given_missing_or_expired_session_when_replaced_should_refuse() {
        BffSessionStore store = store();
        BffSession v1 = session("k");

        assertThat(store.replaceIfVersion(next(v1, "at-2", 2L), 1L).block()).isFalse();
        store.save(v1, ttl()).block();
        advance(ttl().plusSeconds(1));
        assertThat(store.replaceIfVersion(next(v1, "at-2", 2L), 1L).block()).isFalse();
        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a session WHEN replaced SHOULD keep its time to live, not extend it")
    void given_session_when_replaced_should_keep_its_ttl() {
        BffSessionStore store = store();
        BffSession v1 = session("k");
        store.save(v1, ttl()).block();
        advance(ttl().multipliedBy(6).dividedBy(10));
        store.replaceIfVersion(next(v1, "at-2", 2L), 1L).block();
        assertThat(store.find("k").blockOptional()).isPresent();
        advance(ttl().multipliedBy(6).dividedBy(10));

        assertThat(store.find("k").blockOptional()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN two writers expecting the same version WHEN they replace at once SHOULD let exactly one win")
    void given_two_writers_with_same_expected_version_should_let_one_win() {
        BffSessionStore store = store();
        BffSession v1 = session("k");
        for (int i = 0; i < 10; i++) {
            long version = i + 1;
            BffSession current = next(v1, "at-" + version, version);
            store.save(current, LONG).block();

            List<Boolean> results = Flux.merge(
                            store.replaceIfVersion(next(current, "a", version + 1), version)
                                    .subscribeOn(Schedulers.parallel()),
                            store.replaceIfVersion(next(current, "b", version + 1), version)
                                    .subscribeOn(Schedulers.parallel()))
                    .collectList()
                    .block(Duration.ofSeconds(10));

            assertThat(results).as("round %s", i).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    @DisplayName("GIVEN a held lease WHEN the session is replaced SHOULD keep the lease with its owner")
    void given_held_lease_when_session_replaced_should_keep_the_lease() {
        BffSessionStore store = store();
        BffSession v1 = session("k");
        store.save(v1, LONG).block();
        store.tryLease("k", "a", LONG).block();
        store.replaceIfVersion(next(v1, "at-2", 2L), 1L).block();

        assertThat(store.tryLease("k", "b", LONG).block()).isFalse();
        store.releaseLease("k", "a").block();
        assertThat(store.tryLease("k", "b", LONG).block()).isTrue();
    }

    // ---- lifecycle: sid and subject indexes, jti cache -------------------------------------------

    static BffSession of(String key, String provider, String subject, String sid) {
        BffSession b = session(key);
        return new BffSession(
                key,
                subject,
                provider,
                b.application(),
                sid,
                b.name(),
                b.email(),
                b.picture(),
                b.accessToken(),
                b.accessTokenExpiresAt(),
                b.refreshToken(),
                b.idToken(),
                b.created(),
                b.lastSeen(),
                b.version());
    }

    @Test
    @DisplayName(
            "GIVEN sessions with several sids WHEN deleting by sid SHOULD delete only that provider's matching ones and report their keys")
    void given_sessions_when_deleting_by_sid_should_delete_only_matching() {
        BffSessionStore store = store();
        store.save(of("a1", "google", "u1", "s1"), LONG).block();
        store.save(of("a2", "google", "u1", "s1"), LONG).block();
        store.save(of("b1", "google", "u1", "s2"), LONG).block();
        store.save(of("c1", "other", "u1", "s1"), LONG).block();

        assertThat(store.deleteBySid("google", "s1").block()).containsExactlyInAnyOrder("a1", "a2");

        assertThat(store.find("a1").blockOptional()).isEmpty();
        assertThat(store.find("a2").blockOptional()).isEmpty();
        assertThat(store.find("b1").blockOptional()).isPresent();
        assertThat(store.find("c1").blockOptional()).isPresent();
    }

    @Test
    @DisplayName(
            "GIVEN sessions of two subjects WHEN deleting by subject SHOULD delete all of that subject's and no one else's")
    void given_sessions_when_deleting_by_subject_should_delete_only_that_subject() {
        BffSessionStore store = store();
        store.save(of("a1", "google", "u1", "s1"), LONG).block();
        store.save(of("a2", "google", "u1", null), LONG).block();
        store.save(of("b1", "google", "u2", "s3"), LONG).block();
        store.save(of("c1", "other", "u1", "s4"), LONG).block();

        assertThat(store.deleteBySubject("google", "u1").block()).containsExactlyInAnyOrder("a1", "a2");

        assertThat(store.find("a1").blockOptional()).isEmpty();
        assertThat(store.find("a2").blockOptional()).isEmpty();
        assertThat(store.find("b1").blockOptional()).isPresent();
        assertThat(store.find("c1").blockOptional()).isPresent();
        // the other index lost them too
        assertThat(store.deleteBySid("google", "s1").block()).isEmpty();
    }

    @Test
    @DisplayName(
            "GIVEN nothing matches WHEN deleting by sid or subject SHOULD answer an empty list, and a repeat reports nothing")
    void given_no_match_when_deleting_should_answer_empty_list() {
        BffSessionStore store = store();
        store.save(of("a1", "google", "u1", "s1"), LONG).block();

        assertThat(store.deleteBySid("google", "nope").block()).isEmpty();
        assertThat(store.deleteBySubject("google", "nope").block()).isEmpty();
        assertThat(store.deleteBySubject("google", "u1").block()).containsExactly("a1");
        assertThat(store.deleteBySubject("google", "u1").block()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN a session WHEN deleted by key SHOULD leave the indexes clean")
    void given_session_deleted_by_key_should_leave_indexes_clean() {
        BffSessionStore store = store();
        store.save(of("a1", "google", "u1", "s1"), LONG).block();
        store.delete("a1").block();

        assertThat(store.deleteBySid("google", "s1").block()).isEmpty();
        assertThat(store.deleteBySubject("google", "u1").block()).isEmpty();
    }

    @Test
    @DisplayName("GIVEN an expired session WHEN deleting by subject SHOULD not report it, and live ones still go")
    void given_expired_session_when_deleting_should_not_report_it() {
        BffSessionStore store = store();
        store.save(of("old", "google", "u1", "s1"), ttl()).block();
        store.save(of("new", "google", "u1", "s2"), LONG).block();
        advance(ttl().plusSeconds(1));

        assertThat(store.deleteBySubject("google", "u1").block()).containsExactly("new");
    }

    @Test
    @DisplayName("GIVEN a session touched past its first expiry WHEN deleting by sid SHOULD still find it")
    void given_touched_session_when_deleting_by_sid_should_still_find_it() {
        BffSessionStore store = store();
        Duration sixTenths = ttl().multipliedBy(6).dividedBy(10);
        store.save(of("a1", "google", "u1", "s1"), ttl()).block();
        advance(sixTenths);
        store.touch("a1", Instant.parse("2026-01-01T01:00:00Z"), ttl()).block();
        advance(sixTenths);

        assertThat(store.deleteBySid("google", "s1").block()).containsExactly("a1");
    }

    @Test
    @DisplayName(
            "GIVEN a session saved again under the same key with another sid WHEN deleting by the old sid SHOULD find nothing, by the new one the key")
    void given_session_saved_again_with_other_sid_should_move_in_the_index() {
        BffSessionStore store = store();
        store.save(of("a1", "google", "u1", "s1"), LONG).block();
        store.save(of("a1", "google", "u1", "s2"), LONG).block();

        assertThat(store.deleteBySid("google", "s1").block()).isEmpty();
        assertThat(store.deleteBySid("google", "s2").block()).containsExactly("a1");
    }

    @Test
    @DisplayName("GIVEN a session replaced by version with another sid WHEN deleting SHOULD follow the new sid")
    void given_session_replaced_by_version_with_other_sid_should_move_in_the_index() {
        BffSessionStore store = store();
        BffSession v1 = of("a1", "google", "u1", "s1");
        store.save(v1, LONG).block();
        BffSession v2 = new BffSession(
                "a1",
                "u1",
                "google",
                v1.application(),
                "s9",
                v1.name(),
                v1.email(),
                v1.picture(),
                "at-2",
                v1.accessTokenExpiresAt(),
                v1.refreshToken(),
                v1.idToken(),
                v1.created(),
                v1.lastSeen(),
                2L);
        assertThat(store.replaceIfVersion(v2, 1L).block()).isTrue();

        assertThat(store.deleteBySid("google", "s1").block()).isEmpty();
        assertThat(store.deleteBySid("google", "s9").block()).containsExactly("a1");
    }

    @Test
    @DisplayName("GIVEN sessions WHEN finding by subject SHOULD return the live ones of that provider and subject only")
    void given_sessions_when_finding_by_subject_should_return_live_ones() {
        BffSessionStore store = store();
        store.save(of("a1", "google", "u1", "s1"), LONG).block();
        store.save(of("a2", "google", "u1", "s2"), LONG).block();
        store.save(of("gone", "google", "u1", "s3"), ttl()).block();
        store.save(of("b1", "google", "u2", "s4"), LONG).block();
        store.save(of("c1", "other", "u1", "s5"), LONG).block();
        store.delete("gone").block();

        assertThat(store.findBySubject("google", "u1")
                        .map(BffSession::key)
                        .collectList()
                        .block())
                .containsExactlyInAnyOrder("a1", "a2");
    }

    @Test
    @DisplayName(
            "GIVEN many sessions of one subject WHEN two callers delete by subject at once SHOULD report each key to exactly one")
    void given_two_callers_deleting_by_subject_should_report_each_key_once() {
        BffSessionStore store = store();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 20; i++)
                store.save(of("k" + round + "-" + i, "google", "u1", null), LONG)
                        .block();

            List<String> reported = Flux.merge(
                            store.deleteBySubject("google", "u1").subscribeOn(Schedulers.parallel()),
                            store.deleteBySubject("google", "u1").subscribeOn(Schedulers.parallel()))
                    .flatMapIterable(l -> l)
                    .collectList()
                    .block(Duration.ofSeconds(10));

            assertThat(reported).as("round %s", round).hasSize(20).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("GIVEN a jti WHEN marked twice SHOULD be new once, and per provider")
    void given_jti_when_marked_twice_should_be_new_once() {
        BffSessionStore store = store();

        assertThat(store.markJtiSeen("google", "j1", LONG).block()).isTrue();
        assertThat(store.markJtiSeen("google", "j1", LONG).block()).isFalse();
        assertThat(store.markJtiSeen("other", "j1", LONG).block()).isTrue();
        assertThat(store.markJtiSeen("google", "j2", LONG).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN a jti WHEN forgotten or past its ttl SHOULD be new again")
    void given_jti_forgotten_or_expired_should_be_new_again() {
        BffSessionStore store = store();
        store.markJtiSeen("google", "j1", LONG).block();
        store.forgetJti("google", "j1").block();
        assertThat(store.markJtiSeen("google", "j1", LONG).block()).isTrue();

        store.markJtiSeen("google", "j2", ttl()).block();
        advance(ttl().plusSeconds(1));
        assertThat(store.markJtiSeen("google", "j2", LONG).block()).isTrue();
    }

    @Test
    @DisplayName("GIVEN one jti WHEN many callers mark it at once SHOULD let exactly one win")
    void given_one_jti_when_many_mark_it_should_let_one_win() {
        BffSessionStore store = store();

        List<Boolean> results = Flux.merge(Stream.generate(
                                () -> store.markJtiSeen("google", "race", LONG).subscribeOn(Schedulers.parallel()))
                        .limit(8)
                        .toList())
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(results).filteredOn(b -> b).hasSize(1);
    }
}
