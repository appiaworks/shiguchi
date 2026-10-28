package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wraps a shared session store so that the access, refresh and ID token of every session are
 * ciphertext in it ({@link BffTokenCipher}) and plaintext for every caller. Subject, sid, provider,
 * timestamps, version and the lease fields stay readable: they are indexes or are needed by the
 * admin view. A session whose tokens do not decrypt (no key, another key ring, a plaintext row from
 * before encryption was on) reads as absent, so the browser signs in again; the row is left alone
 * because another instance may hold the key. One warning per failure kind and minute names the
 * reason and key id, never a value.
 *
 * <p>The compare-and-set of the stores compares the {@code version} column only, never a token, so
 * it works unchanged on ciphertext. Deletes, leases, logins and {@code jti} records carry no
 * tokens and pass through.
 */
final class EncryptingBffSessionStore implements BffSessionStore, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EncryptingBffSessionStore.class);

    private static final long WARN_EVERY_MS = 60_000;

    static final String ACCESS = "access_token";
    static final String REFRESH = "refresh_token";
    static final String ID = "id_token";

    private final BffSessionStore delegate;
    private final BffTokenCipher cipher;
    private final LongSupplier millis;
    private final Map<BffTokenCipher.Failure, Long> lastWarned = new EnumMap<>(BffTokenCipher.Failure.class);

    EncryptingBffSessionStore(BffSessionStore delegate, BffTokenCipher cipher) {
        this(delegate, cipher, System::currentTimeMillis);
    }

    EncryptingBffSessionStore(BffSessionStore delegate, BffTokenCipher cipher, LongSupplier millis) {
        this.delegate = delegate;
        this.cipher = cipher;
        this.millis = millis;
    }

    /** The store as the BFF should use it: wrapped when the settings call for encryption. */
    static BffSessionStore wrap(BffSessionStore store, BffProperties.Session settings) {
        BffTokenCipher cipher = BffTokenCipher.from(settings);
        return cipher == null ? store : new EncryptingBffSessionStore(store, cipher);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (delegate instanceof InitializingBean bean) bean.afterPropertiesSet();
    }

    @Override
    public void destroy() throws Exception {
        if (delegate instanceof DisposableBean bean) bean.destroy();
    }

    @Override
    public Mono<Void> save(BffSession session, Duration ttl) {
        return Mono.defer(() -> delegate.save(seal(session), ttl));
    }

    @Override
    public Mono<BffSession> find(String key) {
        return delegate.find(key).flatMap(s -> Mono.justOrEmpty(open(s)));
    }

    @Override
    public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
        return delegate.touch(key, lastSeen, ttl);
    }

    @Override
    public Mono<Void> delete(String key) {
        return delegate.delete(key);
    }

    @Override
    public Mono<Void> saveLogin(BffLogin login, Duration ttl) {
        return delegate.saveLogin(login, ttl);
    }

    @Override
    public Mono<BffLogin> takeLogin(String state) {
        return delegate.takeLogin(state);
    }

    @Override
    public Mono<Boolean> tryLease(String key, String owner, Duration ttl) {
        return delegate.tryLease(key, owner, ttl);
    }

    @Override
    public Mono<Void> releaseLease(String key, String owner) {
        return delegate.releaseLease(key, owner);
    }

    @Override
    public Mono<Boolean> replaceIfVersion(BffSession next, long expectedVersion) {
        return Mono.defer(() -> delegate.replaceIfVersion(seal(next), expectedVersion));
    }

    @Override
    public Flux<BffSession> findBySubject(String provider, String subject) {
        return delegate.findBySubject(provider, subject).flatMap(s -> Mono.justOrEmpty(open(s)));
    }

    @Override
    public Mono<List<String>> deleteBySid(String provider, String sid) {
        return delegate.deleteBySid(provider, sid);
    }

    @Override
    public Mono<List<String>> deleteBySubject(String provider, String subject) {
        return delegate.deleteBySubject(provider, subject);
    }

    @Override
    public Mono<Boolean> markJtiSeen(String provider, String jti, Duration ttl) {
        return delegate.markJtiSeen(provider, jti, ttl);
    }

    @Override
    public Mono<Void> forgetJti(String provider, String jti) {
        return delegate.forgetJti(provider, jti);
    }

    private BffSession seal(BffSession s) {
        return withTokens(
                s,
                cipher.encrypt(s.key(), ACCESS, s.accessToken()),
                cipher.encrypt(s.key(), REFRESH, s.refreshToken()),
                cipher.encrypt(s.key(), ID, s.idToken()));
    }

    /** The session with plaintext tokens, or {@code null} when any token does not decrypt. */
    private BffSession open(BffSession s) {
        try {
            return withTokens(
                    s,
                    cipher.decrypt(s.key(), ACCESS, s.accessToken()),
                    cipher.decrypt(s.key(), REFRESH, s.refreshToken()),
                    cipher.decrypt(s.key(), ID, s.idToken()));
        } catch (BffTokenCipher.DecryptionException e) {
            warn(e);
            return null;
        }
    }

    private void warn(BffTokenCipher.DecryptionException e) {
        long now = millis.getAsLong();
        boolean due;
        synchronized (lastWarned) {
            Long last = lastWarned.get(e.failure());
            due = last == null || now - last >= WARN_EVERY_MS;
            if (due) lastWarned.put(e.failure(), now);
        }
        if (due) {
            log.warn(
                    "BFF: a stored session was ignored because its tokens could not be decrypted: {}; the user"
                            + " signs in again (logged at most once a minute per reason)",
                    e.getMessage());
        }
    }

    private static BffSession withTokens(BffSession s, String access, String refresh, String id) {
        return new BffSession(
                s.key(),
                s.subject(),
                s.provider(),
                s.application(),
                s.sid(),
                s.name(),
                s.email(),
                s.picture(),
                access,
                s.accessTokenExpiresAt(),
                refresh,
                id,
                s.created(),
                s.lastSeen(),
                s.version(),
                s.acr(),
                s.amr(),
                s.authTime(),
                s.claims(),
                s.userAgent());
    }
}
