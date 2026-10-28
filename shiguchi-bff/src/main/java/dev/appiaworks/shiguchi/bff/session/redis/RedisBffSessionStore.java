package dev.appiaworks.shiguchi.bff.session.redis;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.bff.session.BffLogin;
import dev.appiaworks.shiguchi.bff.session.BffSession;
import dev.appiaworks.shiguchi.bff.session.BffSessionClaims;
import dev.appiaworks.shiguchi.bff.session.BffSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sessions and logins in Redis, shared by every gateway instance. A session is a hash at
 * {@code <prefix>session:<key>}, a login a hash at {@code <prefix>login:<state>}; fields are
 * plain strings (times are epoch milliseconds, absent values are left out), so nothing depends on
 * a JSON library. The time to live is a {@code PEXPIRE} set in the same Lua script that writes
 * the hash, so a session never exists without an expiry. Redis expires keys on its own clock:
 * this store reads no clock.
 *
 * <p>{@code touch} and {@code takeLogin} are scripts: a touch of a key that is gone does nothing
 * (no resurrection), and {@code takeLogin} reads and deletes in one step so a login is single use
 * across instances. Only commands of Redis 5 are used.
 *
 * <p>Two sets index the sessions, {@code <prefix>sid:<provider>:<sid>} and
 * {@code <prefix>sub:<provider>:<subject>}, holding session keys. Every command touches one key, so
 * the store runs on a Redis Cluster as well as on a single Redis. The index updates are therefore
 * <em>not</em> atomic with the session write, and are ordered so that an index never misses a live
 * session: on {@code save}, {@code replaceIfVersion} and {@code touch} the key is added to the sets
 * (and their expiry stretched to at least the session's) <em>before</em> the session is written;
 * on {@code delete} the session is deleted first and removed from the sets after, and a failed
 * removal is ignored. A set may therefore hold a key whose session is gone, expired, or now belongs
 * to another subject or sid; every lookup checks the session it finds against the set it came from
 * and removes the entries that do not match, so such entries are never counted, never deleted by
 * mistake, and cost one read to clean. The other way round, a live session missing from a set, cannot
 * happen. Two callers racing to end the same session see disjoint answers, because only the one
 * whose {@code DEL} removed the hash reports the key.
 *
 * <p>The refresh lease is a plain key, {@code SET <prefix>lease:<key> <owner> NX PX}, released by a
 * compare-and-delete script; {@code replaceIfVersion} is a script that checks the hash's
 * {@code version} and rewrites it with the remaining time to live. A seen logout-token
 * {@code jti} is a plain key too, {@code SET <prefix>jti:<provider>:<jti> NX PX}.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class RedisBffSessionStore implements BffSessionStore {

    /** KEYS[1] the hash; ARGV[1] ttl ms, ARGV[2..] field/value pairs. Replaces the whole hash (logins). */
    private static final RedisScript<Long> SAVE = new DefaultRedisScript<>(
            "redis.call('DEL', KEYS[1]) redis.call('HSET', KEYS[1], unpack(ARGV, 2))"
                    + " redis.call('PEXPIRE', KEYS[1], ARGV[1]) return 1",
            Long.class);

    /**
     * KEYS[1] an index set; ARGV[1] ttl ms, [2] session key. Adds the key and stretches the set's
     * expiry to at least the ttl (Redis 5 has no {@code PEXPIRE GT}).
     */
    private static final RedisScript<Long> LINK = new DefaultRedisScript<>(
            "redis.call('SADD', KEYS[1], ARGV[2])"
                    + " if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[1]) then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end"
                    + " return 1",
            Long.class);

    /** KEYS[1] the hash; ARGV[1] ttl ms, [2] lastSeen millis. Does nothing when the key is gone. */
    private static final RedisScript<Long> TOUCH = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('HSET', KEYS[1], 'lastSeen', ARGV[2])"
                    + " redis.call('PEXPIRE', KEYS[1], ARGV[1]) return 1 end return 0",
            Long.class);

    /** KEYS[1] a key. Its remaining time to live in milliseconds (negative when none or absent). */
    private static final RedisScript<Long> PTTL =
            new DefaultRedisScript<>("return redis.call('PTTL', KEYS[1])", Long.class);

    /** KEYS[1] the lease key; ARGV[1] owner. Deletes the lease only if ARGV[1] still holds it. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);

    /**
     * KEYS[1] the session hash; ARGV[1] expected version, [2..] field/value pairs. Replaces the hash
     * when its version matches, keeping the remaining time to live.
     */
    private static final RedisScript<Long> REPLACE_IF_VERSION = new DefaultRedisScript<>(
            "if redis.call('HGET', KEYS[1], 'version') ~= ARGV[1] then return 0 end"
                    + " local ttl = redis.call('PTTL', KEYS[1])"
                    + " redis.call('DEL', KEYS[1]) redis.call('HSET', KEYS[1], unpack(ARGV, 2))"
                    + " if ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) end return 1",
            Long.class);

    /** KEYS[1] the login hash. Returns its fields and deletes it, as one step. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TAKE = new DefaultRedisScript<>(
            "local v = redis.call('HGETALL', KEYS[1]) if #v > 0 then redis.call('DEL', KEYS[1]) end return v",
            List.class);

    private final ReactiveStringRedisTemplate redis;
    private final String prefix;
    private final String sessionPrefix;
    private final String loginPrefix;
    private final String leasePrefix;
    private final String jtiPrefix;

    /**
     * @param redis the Redis client
     * @param keyPrefix prefix of every key, such as {@code gw:bff:}
     */
    public RedisBffSessionStore(ReactiveStringRedisTemplate redis, String keyPrefix) {
        this.redis = redis;
        String p = keyPrefix == null ? "" : keyPrefix;
        this.prefix = p;
        this.sessionPrefix = p + "session:";
        this.loginPrefix = p + "login:";
        this.leasePrefix = p + "lease:";
        this.jtiPrefix = p + "jti:";
    }

    @Override
    public Mono<Void> save(BffSession s, Duration ttl) {
        long ms = ms(ttl);
        return link(s.provider(), s.subject(), s.sid(), s.key(), ms)
                .then(Mono.defer(
                        () -> redis.execute(SAVE, List.of(sessionPrefix + s.key()), args(Long.toString(ms), fields(s)))
                                .then()));
    }

    private static Map<String, String> fields(BffSession s) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "key", s.key());
        put(f, "subject", s.subject());
        put(f, "provider", s.provider());
        put(f, "application", s.application());
        put(f, "sid", s.sid());
        put(f, "name", s.name());
        put(f, "email", s.email());
        put(f, "picture", s.picture());
        put(f, "accessToken", s.accessToken());
        put(f, "accessTokenExpiresAt", millis(s.accessTokenExpiresAt()));
        put(f, "refreshToken", s.refreshToken());
        put(f, "idToken", s.idToken());
        put(f, "created", millis(s.created()));
        put(f, "lastSeen", millis(s.lastSeen()));
        put(f, "version", Long.toString(s.version()));
        put(f, "acr", s.acr());
        put(f, "amr", s.amr().isEmpty() ? null : String.join(" ", s.amr()));
        put(f, "authTime", millis(s.authTime()));
        put(f, "claims", BffSessionClaims.toJson(s.claims()));
        put(f, "userAgent", s.userAgent());
        return f;
    }

    @Override
    public Mono<Boolean> tryLease(String key, String owner, Duration ttl) {
        return redis.opsForValue().setIfAbsent(leasePrefix + key, owner, Duration.ofMillis(ms(ttl)));
    }

    @Override
    public Mono<Void> releaseLease(String key, String owner) {
        return redis.execute(RELEASE, List.of(leasePrefix + key), List.of(owner))
                .then();
    }

    @Override
    public Mono<Boolean> replaceIfVersion(BffSession next, long expectedVersion) {
        String hash = sessionPrefix + next.key();
        return redis.execute(PTTL, List.of(hash))
                .next()
                .defaultIfEmpty(-2L)
                .flatMap(left -> (left > 0
                                ? link(next.provider(), next.subject(), next.sid(), next.key(), left)
                                : Mono.<Void>empty())
                        .then(Mono.defer(() -> redis.execute(
                                        REPLACE_IF_VERSION,
                                        List.of(hash),
                                        args(Long.toString(expectedVersion), fields(next)))
                                .next()
                                .map(r -> r == 1L)
                                .defaultIfEmpty(false))));
    }

    @Override
    public Mono<BffSession> find(String key) {
        return redis.<String, String>opsForHash()
                .entries(sessionPrefix + key)
                .collectMap(e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue()))
                .filter(h -> !h.isEmpty())
                .map(RedisBffSessionStore::session);
    }

    @Override
    public Mono<Void> touch(String key, Instant lastSeen, Duration ttl) {
        long ms = ms(ttl);
        return identity(key)
                .flatMap(id -> link(id[0], id[1], id[2], key, ms))
                .then(Mono.defer(() -> redis.execute(
                                TOUCH,
                                List.of(sessionPrefix + key),
                                List.of(Long.toString(ms), Long.toString(lastSeen.toEpochMilli())))
                        .then()));
    }

    @Override
    public Mono<Void> delete(String key) {
        return remove(key).then();
    }

    @Override
    public Flux<BffSession> findBySubject(String provider, String subject) {
        String index = index("sub", provider, subject);
        return redis.opsForSet()
                .members(index)
                .flatMap(key -> find(key)
                        .filter(s -> provider.equals(s.provider()) && subject.equals(s.subject()))
                        .switchIfEmpty(Mono.defer(() -> prune(index, key))));
    }

    @Override
    public Mono<List<String>> deleteBySid(String provider, String sid) {
        return deleteByIndex(index("sid", provider, sid), s -> provider.equals(s.provider()) && sid.equals(s.sid()));
    }

    @Override
    public Mono<List<String>> deleteBySubject(String provider, String subject) {
        return deleteByIndex(
                index("sub", provider, subject), s -> provider.equals(s.provider()) && subject.equals(s.subject()));
    }

    /**
     * Ends the live sessions of an index set that still belong to it, and drops the other members.
     * Only the caller whose {@code DEL} removed a session reports its key.
     */
    private Mono<List<String>> deleteByIndex(String index, Predicate<BffSession> belongs) {
        return redis.opsForSet()
                .members(index)
                .flatMap(key -> find(key)
                        .filter(belongs)
                        .switchIfEmpty(Mono.defer(() -> prune(index, key)))
                        .flatMap(s -> remove(key))
                        .filter(Boolean::booleanValue)
                        .map(deleted -> key))
                .collectList();
    }

    /** Removes a dead or mismatched member from an index, ignoring a failure (the next lookup retries). */
    private Mono<BffSession> prune(String index, String key) {
        return redis.opsForSet()
                .remove(index, key)
                .onErrorResume(e -> Mono.empty())
                .then(Mono.<BffSession>empty());
    }

    /** {@code provider, subject, sid} of a stored session ({@code null} for an absent field), empty when it is gone. */
    private Mono<String[]> identity(String key) {
        return redis.<String, String>opsForHash()
                .multiGet(sessionPrefix + key, List.of("provider", "subject", "sid"))
                .flatMap(f -> f.get(0) == null && f.get(1) == null && f.get(2) == null
                        ? Mono.<String[]>empty()
                        : Mono.just(new String[] {f.get(0), f.get(1), f.get(2)}));
    }

    /** Adds a session key to its subject and sid sets and stretches their expiry; run before the session is written. */
    private Mono<Void> link(String provider, String subject, String sid, String key, long ttlMs) {
        Mono<Void> bySubject = isBlank(subject) || isBlank(provider)
                ? Mono.empty()
                : redis.execute(LINK, List.of(index("sub", provider, subject)), List.of(Long.toString(ttlMs), key))
                        .then();
        Mono<Void> bySid = isBlank(sid) || isBlank(provider)
                ? Mono.empty()
                : redis.execute(LINK, List.of(index("sid", provider, sid)), List.of(Long.toString(ttlMs), key))
                        .then();
        return bySubject.then(bySid);
    }

    /**
     * Deletes the session, then removes it from its sets; a failure of that second step is ignored
     * because lookups prune the entry. {@code true} when this call's {@code DEL} removed the hash.
     */
    private Mono<Boolean> remove(String key) {
        return identity(key)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(id -> redis.delete(sessionPrefix + key).flatMap(n -> {
                    if (n == 0L) return Mono.just(false);
                    if (id.isEmpty()) return Mono.just(true);
                    String[] f = id.get();
                    Mono<Void> bySubject = isBlank(f[1]) || isBlank(f[0])
                            ? Mono.empty()
                            : redis.opsForSet()
                                    .remove(index("sub", f[0], f[1]), key)
                                    .onErrorResume(e -> Mono.empty())
                                    .then();
                    Mono<Void> bySid = isBlank(f[2]) || isBlank(f[0])
                            ? Mono.empty()
                            : redis.opsForSet()
                                    .remove(index("sid", f[0], f[2]), key)
                                    .onErrorResume(e -> Mono.empty())
                                    .then();
                    return bySubject.then(bySid).thenReturn(true);
                }));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    private String index(String kind, String provider, String value) {
        return prefix + kind + ":" + provider + ":" + value;
    }

    @Override
    public Mono<Boolean> markJtiSeen(String provider, String jti, Duration ttl) {
        return redis.opsForValue().setIfAbsent(jtiPrefix + provider + ":" + jti, "1", Duration.ofMillis(ms(ttl)));
    }

    @Override
    public Mono<Void> forgetJti(String provider, String jti) {
        return redis.delete(jtiPrefix + provider + ":" + jti).then();
    }

    @Override
    public Mono<Void> saveLogin(BffLogin l, Duration ttl) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "state", l.state());
        put(f, "codeVerifier", l.codeVerifier());
        put(f, "nonce", l.nonce());
        put(f, "provider", l.provider());
        put(f, "application", l.application());
        put(f, "returnTo", l.returnTo());
        put(f, "redirectUri", l.redirectUri());
        put(f, "created", millis(l.created()));
        put(f, "maxAge", l.maxAge() == null ? null : Long.toString(l.maxAge()));
        return redis.execute(SAVE, List.of(loginPrefix + l.state()), args(Long.toString(ms(ttl)), f))
                .then();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Mono<BffLogin> takeLogin(String state) {
        return redis.execute(TAKE, List.of(loginPrefix + state)).next().flatMap(flat -> {
            Map<String, String> h = new LinkedHashMap<>();
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                h.put(String.valueOf(flat.get(i)), String.valueOf(flat.get(i + 1)));
            }
            return h.isEmpty() ? Mono.empty() : Mono.just(login(h));
        });
    }

    private static List<String> args(String first, Map<String, String> fields) {
        List<String> args = new ArrayList<>();
        args.add(first);
        fields.forEach((k, v) -> {
            args.add(k);
            args.add(v);
        });
        return args;
    }

    private static long ms(Duration ttl) {
        return Math.max(1, ttl.toMillis());
    }

    private static void put(Map<String, String> fields, String name, String value) {
        if (value != null) fields.put(name, value);
    }

    private static String millis(Instant t) {
        return t == null ? null : Long.toString(t.toEpochMilli());
    }

    private static Instant instant(String millis) {
        return millis == null ? null : Instant.ofEpochMilli(Long.parseLong(millis));
    }

    private static BffSession session(Map<String, String> h) {
        return new BffSession(
                h.get("key"),
                h.get("subject"),
                h.get("provider"),
                h.get("application"),
                h.get("sid"),
                h.get("name"),
                h.get("email"),
                h.get("picture"),
                h.get("accessToken"),
                instant(h.get("accessTokenExpiresAt")),
                h.get("refreshToken"),
                h.get("idToken"),
                instant(h.get("created")),
                instant(h.get("lastSeen")),
                Long.parseLong(h.getOrDefault("version", "0")),
                h.get("acr"),
                amr(h.get("amr")),
                instant(h.get("authTime")),
                BffSessionClaims.fromJson(h.get("claims")),
                h.get("userAgent"));
    }

    /** The {@code amr} values are space-separated (RFC 8176 values contain none); an absent field is no methods. */
    private static List<String> amr(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return List.of(joined.trim().split("\s+"));
    }

    private static BffLogin login(Map<String, String> h) {
        return new BffLogin(
                h.get("state"),
                h.get("codeVerifier"),
                h.get("nonce"),
                h.get("provider"),
                h.get("application"),
                h.get("returnTo"),
                h.get("redirectUri"),
                instant(h.get("created")),
                h.get("maxAge") == null ? null : Long.parseLong(h.get("maxAge")));
    }
}
