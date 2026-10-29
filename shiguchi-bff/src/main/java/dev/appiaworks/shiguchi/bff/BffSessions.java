package dev.appiaworks.shiguchi.bff;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Ends browser sessions from the application's side: for an application that learns of a disabled
 * account through its own channel and does not wait for the issuer's back-channel logout. A bean
 * exists with {@code shiguchi.bff.enabled=true}. Each session it ends disappears from the
 * session store and its push connections close (as for a logout), on every instance sharing the
 * store within the push session check interval, on this instance at once.
 *
 * <p>An access token already sent upstream stays valid at the backends until it expires; ending the
 * session stops new calls and refreshes.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class BffSessions {

    private final BffProperties properties;
    private final BffSessionStore store;
    private final BffLogoutMeter meter;
    private final BffRevocation revocation;

    BffSessions(BffProperties properties, BffSessionStore store, BffLogoutMeter meter, BffRevocation revocation) {
        this.properties = properties;
        this.store = store;
        this.meter = meter;
        this.revocation = revocation;
    }

    /**
     * Ends every session of a user at one provider.
     *
     * @param provider the provider id, as under {@code shiguchi.bff.providers}
     * @param subject the user's {@code sub} at that provider
     * @return how many sessions ended; an error for a provider that is not configured
     */
    public Mono<Integer> revokeSubject(String provider, String subject) {
        if (!known(provider)) return Mono.error(new IllegalArgumentException("unknown provider '" + provider + "'"));
        return store.findBySubject(provider, subject)
                .collectMap(BffSession::key)
                .flatMap(found -> store.deleteBySubject(provider, subject).map(keys -> {
                    meter.count("admin", keys.size());
                    // Only the sessions this call ended; one created between the lookup and the delete is
                    // ended but not revoked at the issuer.
                    revocation.revokeDetached(keys.stream()
                            .map(found::get)
                            .filter(Objects::nonNull)
                            .toList());
                    return keys.size();
                }));
    }

    /**
     * Ends one session.
     *
     * @param handle the session's handle: the key push and the cookie-authenticated caller carry
     *     (Base64url of the SHA-256 of the cookie value)
     * @return {@code true} when a live session ended, {@code false} when there was none
     */
    public Mono<Boolean> revoke(String handle) {
        if (handle == null || handle.isBlank()) return Mono.just(false);
        return store.find(handle)
                .flatMap(session -> store.delete(handle)
                        .doOnSuccess(v -> {
                            meter.count("admin", 1);
                            revocation.revokeDetached(session);
                        })
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    /** The live sessions of a user at one provider, for the admin view. */
    Flux<BffSession> sessions(String provider, String subject) {
        if (!known(provider)) return Flux.error(new IllegalArgumentException("unknown provider '" + provider + "'"));
        return store.findBySubject(provider, subject);
    }

    boolean known(String provider) {
        return provider != null && properties.getProviders().containsKey(provider);
    }
}
