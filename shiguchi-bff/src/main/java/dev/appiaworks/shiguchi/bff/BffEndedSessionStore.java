package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import dev.appiaworks.shiguchi.security.CookieSessionEnded;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wraps the session store the BFF components write through: every {@link #delete} (logout, a
 * refused refresh, an expired session, a sign-in replacing the previous one) and every key
 * {@link #deleteBySid} or {@link #deleteBySubject} reports afterwards tells the
 * instance with a {@link CookieSessionEnded}, so push can close the sockets bound to that session
 * without waiting for its poll. Everything else passes through, including the optional lease and
 * compare-and-set methods.
 */
final class BffEndedSessionStore implements BffSessionStore {

    private final BffSessionStore delegate;
    private final Consumer<CookieSessionEnded> publisher;

    BffEndedSessionStore(BffSessionStore delegate, Consumer<CookieSessionEnded> publisher) {
        this.delegate = delegate;
        this.publisher = publisher;
    }

    @Override
    public Mono<Void> save(BffSession session, Duration ttl) {
        return delegate.save(session, ttl);
    }

    @Override
    public Mono<BffSession> find(String key) {
        return delegate.find(key);
    }

    @Override
    public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
        return delegate.touch(key, lastSeen, ttl);
    }

    @Override
    public Mono<Void> delete(String key) {
        return delegate.delete(key).then(Mono.fromRunnable(() -> publisher.accept(new CookieSessionEnded(key))));
    }

    @Override
    public Flux<BffSession> findBySubject(String provider, String subject) {
        return delegate.findBySubject(provider, subject);
    }

    @Override
    public Mono<List<String>> deleteBySid(String provider, String sid) {
        return delegate.deleteBySid(provider, sid).doOnNext(this::publish);
    }

    @Override
    public Mono<List<String>> deleteBySubject(String provider, String subject) {
        return delegate.deleteBySubject(provider, subject).doOnNext(this::publish);
    }

    private void publish(List<String> keys) {
        keys.forEach(key -> publisher.accept(new CookieSessionEnded(key)));
    }

    @Override
    public Mono<Boolean> markJtiSeen(String provider, String jti, Duration ttl) {
        return delegate.markJtiSeen(provider, jti, ttl);
    }

    @Override
    public Mono<Void> forgetJti(String provider, String jti) {
        return delegate.forgetJti(provider, jti);
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
        return delegate.replaceIfVersion(next, expectedVersion);
    }
}
