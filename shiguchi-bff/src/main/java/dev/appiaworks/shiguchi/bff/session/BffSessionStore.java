package dev.appiaworks.shiguchi.bff.session;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Where BFF sessions and in-flight logins live. Implementations never block the caller's thread
 * (a blocking client goes to a bounded elastic scheduler) and answer empty for a key that is
 * missing or past its time to live.
 *
 * <p>The time to live is computed by the caller, as the smaller of the idle timeout and what is
 * left of the absolute timeout, and passed in, so a store stays a dumb keyed expiring map. The
 * lifecycle methods ({@link #deleteBySid}, {@link #deleteBySubject}, {@link #findBySubject},
 * {@link #markJtiSeen}) are {@code default} too.
 *
 * <p>The refresh methods ({@link #tryLease}, {@link #releaseLease}, {@link #replaceIfVersion})
 * are {@code default} so the interface stays source compatible, and their defaults fail with
 * {@link UnsupportedOperationException}: a naive default (an in-process lock, a blind write)
 * would let two gateway instances refresh one session at once, and the issuer revokes the whole
 * token family on that. A store that can be shared by several instances must implement them
 * atomically; a store that does not leaves {@code relay} refresh off (an expired access token is
 * then an anonymous request, as before).
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public interface BffSessionStore {

    /**
     * Creates or replaces a session.
     *
     * @param session the session, stored under its {@code key}
     * @param ttl how long it stays visible
     * @return completes when stored
     */
    Mono<Void> save(BffSession session, Duration ttl);

    /**
     * @param key the session key
     * @return the session, or empty when missing or expired
     */
    Mono<BffSession> find(String key);

    /**
     * Records activity: sets {@code lastSeen} and restarts the time to live. Does nothing for a
     * key that is gone.
     *
     * @param key the session key
     * @param lastSeen the new last-seen instant
     * @param ttl the new time to live from now
     * @return completes when done
     */
    Mono<Void> touch(String key, Instant lastSeen, Duration ttl);

    /**
     * @param key the session key
     * @return completes when deleted; deleting a missing key is not an error
     */
    Mono<Void> delete(String key);

    /**
     * @param login the in-flight login, stored under its {@code state}
     * @param ttl how long it stays valid (the login timeout)
     * @return completes when stored
     */
    Mono<Void> saveLogin(BffLogin login, Duration ttl);

    /**
     * Finds and deletes a login in one step: a login record is single use.
     *
     * @param state the {@code state} from the callback
     * @return the login, or empty when missing, expired or already taken
     */
    Mono<BffLogin> takeLogin(String state);

    /**
     * Takes the refresh lease of a session if nobody holds a live one. Not re-entrant: a second
     * call by the same owner while the lease is live also answers {@code false}. The lease ends by
     * itself after {@code ttl} (a holder that died), judged by the store's own clock.
     *
     * @param key the session key
     * @param owner who asks; compared by {@link #releaseLease}
     * @param ttl how long the lease lasts
     * @return {@code true} when the caller now holds the lease; the default signals
     *     {@link UnsupportedOperationException}
     */
    default Mono<Boolean> tryLease(String key, String owner, Duration ttl) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no refresh lease"));
    }

    /**
     * Ends the lease if {@code owner} still holds it; does nothing when someone else holds it (the
     * lease expired and was taken) or nobody does.
     *
     * @param key the session key
     * @param owner who took the lease
     * @return completes when done
     */
    default Mono<Void> releaseLease(String key, String owner) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no refresh lease"));
    }

    /**
     * Replaces a session only if the stored one still has {@code expectedVersion}. The stored time
     * to live is kept. The caller sets {@code next.version()} (one more than the expected one).
     *
     * @param next the session to store under its {@code key}
     * @param expectedVersion the version the caller read
     * @return {@code true} when stored; {@code false} when the session is gone, expired, or its
     *     version differs
     */
    default Mono<Boolean> replaceIfVersion(BffSession next, long expectedVersion) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no compare-and-set"));
    }

    /**
     * The live sessions of one subject at one provider. Used by the admin view; no order is promised.
     *
     * @param provider the provider id
     * @param subject the issuer's {@code sub}
     * @return the sessions; the default signals {@link UnsupportedOperationException}
     */
    default Flux<BffSession> findBySubject(String provider, String subject) {
        return Flux.error(new UnsupportedOperationException(getClass().getName() + " has no subject index"));
    }

    /**
     * Deletes every session of {@code provider} whose {@code sid} is {@code sid} (back-channel logout).
     * Each key is reported by exactly one caller even when instances race, so the caller can publish
     * one "session ended" notice per key.
     *
     * @param provider the provider id
     * @param sid the issuer's session id
     * @return the keys of the sessions this call deleted (empty when none); the default signals
     *     {@link UnsupportedOperationException}
     */
    default Mono<List<String>> deleteBySid(String provider, String sid) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no sid index"));
    }

    /**
     * Deletes every session of {@code provider} whose subject is {@code subject}: back-channel logout
     * without a {@code sid}, and revoke-all. Reports keys as {@link #deleteBySid} does.
     *
     * @param provider the provider id
     * @param subject the issuer's {@code sub}
     * @return the keys of the sessions this call deleted (empty when none); the default signals
     *     {@link UnsupportedOperationException}
     */
    default Mono<List<String>> deleteBySubject(String provider, String subject) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no subject index"));
    }

    /**
     * Records a logout token's {@code jti} so the same token is not accepted twice. Atomic across
     * instances.
     *
     * @param provider the provider id
     * @param jti the token's {@code jti}
     * @param ttl how long to remember it (the token's remaining lifetime plus the clock skew)
     * @return {@code true} when this is the first sighting, {@code false} when it was seen already;
     *     the default signals {@link UnsupportedOperationException}
     */
    default Mono<Boolean> markJtiSeen(String provider, String jti, Duration ttl) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no jti cache"));
    }

    /**
     * Forgets a {@code jti} recorded by {@link #markJtiSeen}: used when the logout it announced
     * could not be carried out, so the issuer's retry of the same token is not refused as a replay.
     *
     * @param provider the provider id
     * @param jti the token's {@code jti}
     * @return completes when done; the default signals {@link UnsupportedOperationException}
     */
    default Mono<Void> forgetJti(String provider, String jti) {
        return Mono.error(new UnsupportedOperationException(getClass().getName() + " has no jti cache"));
    }
}
