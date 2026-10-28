package dev.appiaworks.shiguchi.cursus.redis;

import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushEventCodec;
import dev.appiaworks.shiguchi.cursus.PushException;
import dev.appiaworks.shiguchi.cursus.PushStore;
import dev.appiaworks.shiguchi.cursus.Topic;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * History in a Redis Stream per topic ({@code <prefix>stream:<topic>}): {@code XADD} with
 * an exact {@code MAXLEN history-size} trim that records the last removed id in {@code <prefix>trim:<topic>}
 * (the gap watermark) and the key's {@code EXPIRE} refreshed to {@code history-ttl}, in
 * one script so an append is one round trip. The Stream id ({@code millis-seq}) is the event id,
 * ordered across every instance; a topic with no history gets a local id of the same shape, which
 * nobody resumes from.
 */
public final class RedisPushStore implements PushStore {

    /**
     * Shared Lua prefix of both append scripts. KEYS[1] is the stream, KEYS[2] the topic's trim
     * watermark hash (fields {@code trimmed}: highest id a size trim removed, {@code last}: newest id
     * appended). The trim is exact ({@code MAXLEN N}, never {@code ~}): the watermark has to be the
     * exact last id removed, and {@code ~} removes an unknown number of entries. The entries to go
     * are read with {@code XRANGE ... COUNT over} first, so the last one read is the watermark.
     * ARGV[1] history size, ARGV[2] ttl seconds (0: none). The watermark key outlives the stream by
     * seven days (or lives seven days when the stream has no ttl) so a whole-key expiry stays
     * detectable.
     */
    private static final String TRIM_LIB = trimLib(false);

    /** TRIM_LIB plus the per-event age trim ({@code XTRIM MINID}, Redis 6.2+); only for server-assigned ids. */
    private static final String TRIM_LIB_AGE = trimLib(true);

    private static String trimLib(boolean age) {
        return """
            local function idgt(a, b)
              local a1, a2 = string.match(a, '^(%d+)%-(%d+)$')
              local b1, b2 = string.match(b, '^(%d+)%-(%d+)$')
              a1, a2, b1, b2 = tonumber(a1), tonumber(a2), tonumber(b1), tonumber(b2)
              return a1 > b1 or (a1 == b1 and a2 > b2)
            end
            local function settle(stream, mark, id)
              local over = redis.call('XLEN', stream) - tonumber(ARGV[1])
              if over > 0 then
                local gone = redis.call('XRANGE', stream, '-', '+', 'COUNT', over)
                local removed = gone[#gone][1]
                redis.call('XTRIM', stream, 'MAXLEN', ARGV[1])
                local cur = redis.call('HGET', mark, 'trimmed')
                if (not cur) or idgt(removed, cur) then redis.call('HSET', mark, 'trimmed', removed) end
              end
              local ttl = tonumber(ARGV[2])
              if AGE and ttl > 0 then
                -- TIME is non-deterministic; allowed because Redis 5+ replicates a script's effects, not the script.
                -- Server-assigned ids are ms-seq from the same clock, so ids below now-ttl are older than the ttl.
                local now = redis.call('TIME')
                local ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)
                local minid = (ms - ttl * 1000) .. '-0'
                -- exclusive range needs 6.2 (the same version as XTRIM MINID)
                local old = redis.call('XREVRANGE', stream, '(' .. minid, '-', 'COUNT', 1)
                if old[1] then
                  local removed = old[1][1]
                  local cur = redis.call('HGET', mark, 'trimmed')
                  if (not cur) or idgt(removed, cur) then redis.call('HSET', mark, 'trimmed', removed) end
                  redis.call('XTRIM', stream, 'MINID', minid)
                end
              end
              redis.call('HSET', mark, 'last', id)
              if ttl > 0 then
                redis.call('EXPIRE', stream, ttl)
                redis.call('EXPIRE', mark, ttl + 604800)
              else
                redis.call('EXPIRE', mark, 604800)
              end
            end
            """.replace("AGE and", age ? "true and" : "false and");
    }

    private static final String APPEND_BODY = """
            local id = redis.call('XADD', KEYS[1], '*', unpack(ARGV, 3))
            settle(KEYS[1], KEYS[2], id)
            return id
            """;
    private static final RedisScript<String> APPEND_SCRIPT =
            new DefaultRedisScript<>(TRIM_LIB + APPEND_BODY, String.class);
    private static final RedisScript<String> APPEND_AGE_SCRIPT =
            new DefaultRedisScript<>(TRIM_LIB_AGE + APPEND_BODY, String.class);
    /** Like APPEND with the id in ARGV[3]; answers nil (false) when the stream already holds that id or a larger one. */
    private static final String APPEND_WITH_ID = TRIM_LIB + """
            local ok, id = pcall(redis.call, 'XADD', KEYS[1], ARGV[3], unpack(ARGV, 4))
            if not ok then
              local msg = type(id) == 'table' and id.err or tostring(id)
              if string.find(msg, 'equal or smaller', 1, true) then return false end
              error(id)
            end
            settle(KEYS[1], KEYS[2], id)
            return id
            """;

    private static final RedisScript<String> APPEND_WITH_ID_SCRIPT =
            new DefaultRedisScript<>(APPEND_WITH_ID, String.class);
    /** Redis Cluster: the original single-key append (approximate trim, no watermark key: it would need the stream's slot). */
    private static final String APPEND_CLUSTER = """
            local id = redis.call('XADD', KEYS[1], 'MAXLEN', '~', ARGV[1], '*', unpack(ARGV, 3))
            if tonumber(ARGV[2]) > 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return id
            """;

    private static final RedisScript<String> APPEND_CLUSTER_SCRIPT =
            new DefaultRedisScript<>(APPEND_CLUSTER, String.class);
    private static final String APPEND_WITH_ID_CLUSTER = """
            local ok, id = pcall(redis.call, 'XADD', KEYS[1], 'MAXLEN', '~', ARGV[1], ARGV[3], unpack(ARGV, 4))
            if not ok then
              local msg = type(id) == 'table' and id.err or tostring(id)
              if string.find(msg, 'equal or smaller', 1, true) then return false end
              error(id)
            end
            if tonumber(ARGV[2]) > 0 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return id
            """;
    private static final RedisScript<String> APPEND_WITH_ID_CLUSTER_SCRIPT =
            new DefaultRedisScript<>(APPEND_WITH_ID_CLUSTER, String.class);
    private static final Logger log = LoggerFactory.getLogger(RedisPushStore.class);
    /** KEYS[1] stream, KEYS[2] watermark: size-trim watermark while the stream lives, the last id once it is gone. */
    private static final String TRIMMED_THROUGH = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('HGET', KEYS[2], 'trimmed') end
            return redis.call('HGET', KEYS[2], 'last')
            """;

    private static final RedisScript<String> TRIMMED_THROUGH_SCRIPT =
            new DefaultRedisScript<>(TRIMMED_THROUGH, String.class);
    private static final String LEASE = """
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
            if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end
            return 0
            """;
    private static final RedisScript<Long> LEASE_SCRIPT = new DefaultRedisScript<>(LEASE, Long.class);
    private static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """;
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(RELEASE, Long.class);
    /** KEYS[1] the session hash, ARGV[1] the topic, ARGV[2] the id, ARGV[3] the expiry in ms: forward-only. */
    private static final String ACK = """
            local function parts(id)
              local a, b = string.match(id, '^(%d+)%-(%d+)$')
              if a == nil then return tonumber(id) or 0, 0 end
              return tonumber(a), tonumber(b)
            end
            local cur = redis.call('HGET', KEYS[1], ARGV[1])
            local moved = 1
            if cur then
              local ca, cb = parts(cur)
              local na, nb = parts(ARGV[2])
              if ca > na or (ca == na and cb >= nb) then moved = 0 end
            end
            if moved == 1 then redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) end
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return moved
            """;

    private static final RedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>(ACK, Long.class);
    /** KEYS[1] the stream, ARGV[1] the group: the consumer group exists at the head, the stream created empty when it did not. */
    private static final String GROUP = """
            local ok, err = pcall(redis.call, 'XGROUP', 'CREATE', KEYS[1], ARGV[1], '$', 'MKSTREAM')
            if ok then return 1 end
            if string.find(tostring(err), 'BUSYGROUP') then return 0 end
            error(err)
            """;

    private static final RedisScript<Long> GROUP_SCRIPT = new DefaultRedisScript<>(GROUP, Long.class);

    /** KEYS[1] a session hash, ARGV[1] the topic, ARGV[2] the id: moves an existing cursor (back too), keeping the key's expiry. */
    private static final String SEEK_SESSION = """
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """;

    private static final RedisScript<Long> SEEK_SESSION_SCRIPT = new DefaultRedisScript<>(SEEK_SESSION, Long.class);

    /** KEYS[1] the stream, ARGV[1] the group, ARGV[2] the id: acknowledges every pending entry, then moves the group's position. */
    private static final String SEEK_GROUP = """
            while true do
              local p = redis.call('XPENDING', KEYS[1], ARGV[1], '-', '+', 1000)
              if #p == 0 then break end
              for _, e in ipairs(p) do redis.call('XACK', KEYS[1], ARGV[1], e[1]) end
            end
            redis.call('XGROUP', 'SETID', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """;

    private static final RedisScript<Long> SEEK_GROUP_SCRIPT = new DefaultRedisScript<>(SEEK_GROUP, Long.class);

    /** Idle time given to a released claim: far back, so any member may take it. */
    private static final String RELEASED_IDLE_MS =
            Long.toString(Duration.ofDays(3650).toMillis());

    /**
     * KEYS[1] the stream; ARGV: group, member, idle ms. Releases every entry the member holds, 1000
     * XPENDING entries at a time (inclusive start, skipping the repeated boundary entry). One XCLAIM
     * per entry sets the idle time far back so any member may take it, and RETRYCOUNT keeps the
     * delivery count as it was (a plain XCLAIM would bump it). Returns how many were released.
     */
    private static final String RELEASE_CLAIMS = """
            local stream, group, member, idle = KEYS[1], ARGV[1], ARGV[2], ARGV[3]
            local n, start = 0, '-'
            while true do
              local page = redis.call('XPENDING', stream, group, start, '+', 1000, member)
              for _, p in ipairs(page) do
                if p[1] ~= start then
                  redis.call('XCLAIM', stream, group, member, 0, p[1], 'IDLE', idle, 'RETRYCOUNT', p[4])
                  n = n + 1
                end
              end
              if #page < 1000 then break end
              start = page[#page][1]
            end
            return n
            """;

    private static final RedisScript<Long> RELEASE_CLAIMS_SCRIPT = new DefaultRedisScript<>(RELEASE_CLAIMS, Long.class);

    /**
     * KEYS[1] the stream; ARGV: group, member, max, ackDeadline ms, then the ids the caller has just
     * read fresh with XREADGROUP (they are already in its PEL; XREADGROUP cannot run in a script).
     * The {@code KEY}-ordered claim (mirrors {@code MemoryPushStore}): walks the group's other
     * pending entries in id order, then the fresh ones. A key another member holds live blocks the
     * rest of that key. A fresh entry that cannot be taken is parked: left in the PEL with its idle
     * time set far back and its delivery count at 0, so the next claim by whoever may take it treats
     * it as a first delivery. Returns, per taken entry: id, deliveries, field count, then the fields.
     */
    private static final String CLAIM_KEY = """
            local stream, group, member = KEYS[1], ARGV[1], ARGV[2]
            local max, deadline = tonumber(ARGV[3]), tonumber(ARGV[4])
            local function keyOf(fields)
              for i = 1, #fields, 2 do if fields[i] == 'key' then return fields[i + 1] end end
              return nil
            end
            local out, taken = {}, 0
            local function emit(id, deliveries, fields)
              out[#out + 1] = id
              out[#out + 1] = tostring(deliveries)
              out[#out + 1] = tostring(#fields)
              for i = 1, #fields do out[#out + 1] = fields[i] end
              taken = taken + 1
            end
            local fresh = {}
            for i = 5, #ARGV do fresh[ARGV[i]] = true end
            local pending, start = {}, '-'
            while true do
              local page = redis.call('XPENDING', stream, group, start, '+', 1000)
              for _, p in ipairs(page) do
                if p[1] ~= start then pending[#pending + 1] = p end
              end
              if #page < 1000 then break end
              start = page[#page][1]
            end
            local entries, live = {}, {}
            for _, p in ipairs(pending) do
              local r = redis.call('XRANGE', stream, p[1], p[1])
              if fresh[p[1]] then
                -- handled below, in id order after every older entry
              elseif #r == 0 then
                redis.call('XACK', stream, group, p[1])
              else
                local k = keyOf(r[1][2])
                if k == '' then k = nil end
                entries[#entries + 1] = {id = p[1], consumer = p[2], idle = p[3], count = p[4], key = k}
                if k and p[3] < deadline then
                  if live[k] == nil or live[k] == p[2] then live[k] = p[2] else live[k] = '*' end
                end
              end
            end
            local blocked = {}
            for _, e in ipairs(entries) do
              local k = e.key
              if not (k and blocked[k]) then
                if e.idle < deadline then
                  if k and e.consumer ~= member then blocked[k] = true end
                elseif taken >= max then
                  if k then blocked[k] = true end
                elseif k and live[k] ~= nil and live[k] ~= member then
                  blocked[k] = true
                else
                  local got = redis.call('XCLAIM', stream, group, member, 0, e.id)
                  if #got > 0 then
                    emit(e.id, e.count + 1, got[1][2])
                    if k then live[k] = member end
                  end
                end
              end
            end
            for i = 5, #ARGV do
              local id = ARGV[i]
              local r = redis.call('XRANGE', stream, id, id)
              if #r > 0 then
                local k = keyOf(r[1][2])
                if k == '' then k = nil end
                if k and (blocked[k] or (live[k] ~= nil and live[k] ~= member)) then
                  blocked[k] = true
                  redis.call('XCLAIM', stream, group, member, 0, id, 'IDLE', 315360000000, 'RETRYCOUNT', 0, 'JUSTID')
                else
                  emit(id, 1, r[1][2])
                  if k then live[k] = member end
                end
              end
            end
            return out
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM_KEY_SCRIPT = new DefaultRedisScript<>(CLAIM_KEY, List.class);

    private final ReactiveStringRedisTemplate redis;
    private final String keyPrefix;
    private final String streamPrefix;
    private final String leasePrefix;
    private final String sessionPrefix;
    private final String connPrefix;
    private final String trimPrefix;
    private final boolean cluster;
    /**
     * Redis 6.2+ standalone/Sentinel: appends also trim by age ({@code XTRIM MINID}). Not used by
     * {@link #appendWithId}: caller-supplied ids need not be timestamps.
     */
    private final boolean ageTrim;

    private final AtomicLong localSequence = new AtomicLong();

    public RedisPushStore(ReactiveStringRedisTemplate redis, String keyPrefix) {
        this(redis, keyPrefix, clusterAware(redis));
    }

    RedisPushStore(ReactiveStringRedisTemplate redis, String keyPrefix, boolean cluster) {
        this(redis, keyPrefix, cluster, cluster ? Boolean.FALSE : null);
    }

    /**
     * Test seam: forces cluster mode (single-key scripts, no gap detection) and, when {@code minIdSupported} is
     * non-null, the XTRIM MINID support that is otherwise read from {@code INFO server}.
     */
    RedisPushStore(ReactiveStringRedisTemplate redis, String keyPrefix, boolean cluster, Boolean minIdSupported) {
        this.cluster = cluster;
        this.ageTrim = !cluster && (minIdSupported != null ? minIdSupported : detectMinId(redis));
        if (cluster) {
            log.info("Push: Redis Cluster detected — history trim stays approximate and gap detection is off"
                    + " (the watermark key would need the stream's hash slot)");
        }
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.streamPrefix = keyPrefix + "stream:";
        this.trimPrefix = keyPrefix + "trim:";
        this.leasePrefix = keyPrefix + "lease:";
        this.sessionPrefix = keyPrefix + "session:";
        this.connPrefix = keyPrefix + "conn:";
    }

    /** Reads redis_version from INFO server once; any failure counts as unsupported. */
    private static boolean detectMinId(ReactiveStringRedisTemplate redis) {
        String version = null;
        try {
            Properties info =
                    redis.execute(c -> c.serverCommands().info("server")).blockFirst(Duration.ofSeconds(10));
            version = info == null ? null : info.getProperty("redis_version");
        } catch (RuntimeException e) {
            log.debug("Push: could not read the Redis version", e);
        }
        boolean supported = versionAtLeast62(version);
        if (!supported) {
            log.warn(
                    "Push: Redis {} has no XTRIM MINID (6.2+); history-ttl applies only once a topic is idle",
                    version == null ? "(unknown version)" : version);
        }
        return supported;
    }

    static boolean versionAtLeast62(String version) {
        if (version == null) return false;
        String[] p = version.split("[.-]");
        try {
            int major = Integer.parseInt(p[0]);
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return major > 6 || (major == 6 && minor >= 2);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True when appends also expire single events by age (Redis 6.2+, standalone or Sentinel). */
    boolean ageTrimEnabled() {
        return ageTrim;
    }

    private static boolean clusterAware(ReactiveStringRedisTemplate redis) {
        return redis.getConnectionFactory() instanceof LettuceConnectionFactory lettuce && lettuce.isClusterAware();
    }

    /** The cluster-wide archive over the same Redis and key prefix. */
    public RedisPushArchive archive(Duration retention, int maxScan) {
        return new RedisPushArchive(redis, keyPrefix, retention, maxScan);
    }

    @Override
    public Mono<PushEvent> append(PushEvent event, int historySize, Duration historyTtl) {
        if (historySize <= 0) {
            return Mono.just(event.withId(System.currentTimeMillis() + "-" + localSequence.incrementAndGet()));
        }
        List<String> args = new ArrayList<>();
        args.add(Integer.toString(historySize));
        args.add(Long.toString(historyTtl == null ? 0 : Math.max(0, historyTtl.toSeconds())));
        PushEventCodec.toFields(event).forEach((k, v) -> {
            args.add(k);
            args.add(v);
        });
        return redis.execute(
                        cluster ? APPEND_CLUSTER_SCRIPT : ageTrim ? APPEND_AGE_SCRIPT : APPEND_SCRIPT,
                        cluster ? List.of(key(event.topic())) : List.of(key(event.topic()), trimKey(event.topic())),
                        args)
                .next()
                .map(event::withId);
    }

    @Override
    public boolean explicitIds() {
        return true;
    }

    @Override
    public Mono<PushEvent> appendWithId(PushEvent event, long id, int historySize, Duration historyTtl) {
        String streamId = id + "-0";
        if (historySize <= 0) return Mono.just(event.withId(streamId));
        List<String> args = new ArrayList<>();
        args.add(Integer.toString(historySize));
        args.add(Long.toString(historyTtl == null ? 0 : Math.max(0, historyTtl.toSeconds())));
        args.add(streamId);
        PushEventCodec.toFields(event).forEach((k, v) -> {
            args.add(k);
            args.add(v);
        });
        return redis.execute(
                        cluster ? APPEND_WITH_ID_CLUSTER_SCRIPT : APPEND_WITH_ID_SCRIPT,
                        cluster ? List.of(key(event.topic())) : List.of(key(event.topic()), trimKey(event.topic())),
                        args)
                .next()
                .map(event::withId)
                .switchIfEmpty(Mono.error(() -> PushException.conflict(
                        "event id " + id + " is not above the newest id kept on " + event.topic())));
    }

    @Override
    public Mono<PushEvent> headEvent(Topic topic) {
        return redis.opsForStream()
                .reverseRange(key(topic), Range.unbounded(), Limit.limit().count(1))
                .next()
                .map(r -> PushEventCodec.fromFields(r.getId().getValue(), topic, strings(r.getValue())));
    }

    @Override
    public Flux<PushEvent> range(Topic topic, String afterId, int limit) {
        Range<String> range =
                afterId == null ? Range.unbounded() : Range.rightUnbounded(Range.Bound.inclusive(afterId));
        Flux<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(key(topic), range, Limit.limit().count(afterId == null ? limit : limit + 1));
        return records.filter(r -> afterId == null || !afterId.equals(r.getId().getValue()))
                .take(limit)
                .map(r -> PushEventCodec.fromFields(r.getId().getValue(), topic, strings(r.getValue())));
    }

    @Override
    public boolean persistentSessions() {
        return true;
    }

    @Override
    public String firstId() {
        return "0-0";
    }

    @Override
    public Mono<String> headId(Topic topic) {
        return redis.opsForStream()
                .reverseRange(key(topic), Range.unbounded(), Limit.limit().count(1))
                .next()
                .map(r -> r.getId().getValue());
    }

    @Override
    public Mono<String> cursor(String session, Topic topic) {
        return redis.opsForHash().get(sessionPrefix + session, topic.toString()).map(Object::toString);
    }

    @Override
    public Mono<Void> ack(String session, Topic topic, String id, Duration expiry) {
        long ms = expiry == null ? Duration.ofDays(365).toMillis() : Math.max(1, expiry.toMillis());
        return redis.execute(
                        ACK_SCRIPT, List.of(sessionPrefix + session), List.of(topic.toString(), id, Long.toString(ms)))
                .then();
    }

    @Override
    public Mono<Boolean> seekSession(String session, Topic topic, String id) {
        return redis.execute(SEEK_SESSION_SCRIPT, List.of(sessionPrefix + session), List.of(topic.toString(), id))
                .next()
                .map(n -> n != null && n > 0)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> seekGroup(String group, Topic topic, String id) {
        String key = key(topic);
        return redis.opsForStream()
                .groups(key)
                .onErrorResume(e -> Flux.empty())
                .filter(g -> g.groupName().equals(group))
                .next()
                .flatMap(g -> redis.execute(SEEK_GROUP_SCRIPT, List.of(key), List.of(group, id))
                        .next()
                        .map(n -> n != null && n > 0))
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<SessionCursor> sessions(String prefix, int limit) {
        String pattern = sessionPrefix
                + (prefix == null ? "" : prefix.replace("*", "\\*").replace("?", "\\?")) + "*";
        return redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern)
                        .count(200)
                        .build())
                .sort()
                .concatMap(key -> {
                    String session = key.substring(sessionPrefix.length());
                    Mono<Instant> expires = redis.getExpire(key)
                            .map(ttl -> ttl.isNegative()
                                    ? Instant.MAX
                                    : Instant.now().plus(ttl))
                            .defaultIfEmpty(Instant.MAX);
                    return expires.flatMapMany(at -> redis.opsForHash()
                            .entries(key)
                            .map(e -> new SessionCursor(
                                    session,
                                    Topic.parse(e.getKey().toString()),
                                    e.getValue().toString(),
                                    at)));
                })
                .take(limit);
    }

    @Override
    public Mono<Long> dropSession(String session) {
        String key = sessionPrefix + session;
        return redis.opsForHash().size(key).flatMap(n -> redis.delete(key).thenReturn(n));
    }

    @Override
    public boolean consumerGroups() {
        return true;
    }

    /** The group exists on the topic's stream, created at the head when it did not. */
    private Mono<Void> ensureGroup(String key, String group) {
        return redis.execute(GROUP_SCRIPT, List.of(key), List.of(group)).then();
    }

    @Override
    public Flux<Claimed> claim(
            String group, Topic topic, String member, int max, Duration ackDeadline, Duration expiry) {
        if (max <= 0) return Flux.empty();
        String key = key(topic);
        org.springframework.data.redis.connection.stream.Consumer consumer =
                org.springframework.data.redis.connection.stream.Consumer.from(group, member);
        Flux<Claimed> reclaimed = redis.opsForStream()
                .pending(key, group, Range.unbounded(), max)
                .flatMapMany(pending -> {
                    Map<String, Long> counts = new java.util.HashMap<>();
                    List<org.springframework.data.redis.connection.stream.RecordId> ids = new ArrayList<>();
                    for (org.springframework.data.redis.connection.stream.PendingMessage p : pending) {
                        if (p.getElapsedTimeSinceLastDelivery().compareTo(ackDeadline) < 0) continue;
                        counts.put(p.getIdAsString(), p.getTotalDeliveryCount());
                        ids.add(p.getId());
                    }
                    if (ids.isEmpty()) return Flux.empty();
                    return redis.opsForStream()
                            .claim(
                                    key,
                                    group,
                                    member,
                                    org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions.minIdle(
                                                    ackDeadline)
                                            .ids(ids))
                            .map(r -> new Claimed(
                                    PushEventCodec.fromFields(r.getId().getValue(), topic, strings(r.getValue())),
                                    (int) (counts.getOrDefault(r.getId().getValue(), 0L) + 1)));
                })
                .onErrorResume(e -> Flux.empty());
        return ensureGroup(key, group).thenMany(reclaimed.collectList().flatMapMany(first -> {
            int room = max - first.size();
            Flux<Claimed> fresh = room <= 0
                    ? Flux.empty()
                    : redis.opsForStream()
                            .read(
                                    consumer,
                                    org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                            .count(room),
                                    org.springframework.data.redis.connection.stream.StreamOffset.create(
                                            key,
                                            org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()))
                            .map(r -> new Claimed(
                                    PushEventCodec.fromFields(r.getId().getValue(), topic, strings(r.getValue())), 1))
                            .onErrorResume(e -> Flux.empty());
            return Flux.concat(Flux.fromIterable(first), fresh);
        }));
    }

    @Override
    public boolean keyOrdering() {
        return true;
    }

    @Override
    public Flux<Claimed> claim(
            String group,
            Topic topic,
            String member,
            int max,
            Duration ackDeadline,
            Duration expiry,
            dev.appiaworks.shiguchi.cursus.PushProperties.Ordering ordering) {
        if (ordering != dev.appiaworks.shiguchi.cursus.PushProperties.Ordering.KEY)
            return claim(group, topic, member, max, ackDeadline, expiry);
        if (max <= 0) return Flux.empty();
        String key = key(topic);
        long deadlineMs = ackDeadline == null ? Long.MAX_VALUE / 4 : ackDeadline.toMillis();
        org.springframework.data.redis.connection.stream.Consumer consumer =
                org.springframework.data.redis.connection.stream.Consumer.from(group, member);
        return ensureGroup(key, group)
                .then(Mono.defer(() -> claimKeyRound(key, topic, group, member, consumer, max, deadlineMs, 0)))
                .flatMapMany(Flux::fromIterable)
                .onErrorResume(e -> Flux.empty());
    }

    /** A few rounds: parked fresh entries use up reads without filling the caller's room. */
    private Mono<List<Claimed>> claimKeyRound(
            String key,
            Topic topic,
            String group,
            String member,
            org.springframework.data.redis.connection.stream.Consumer consumer,
            int room,
            long deadlineMs,
            int round) {
        return redis.opsForStream()
                .read(
                        consumer,
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                                .count(room),
                        org.springframework.data.redis.connection.stream.StreamOffset.create(
                                key, org.springframework.data.redis.connection.stream.ReadOffset.lastConsumed()))
                .map(r -> r.getId().getValue())
                .collectList()
                .flatMap(fresh -> {
                    List<String> args =
                            new ArrayList<>(List.of(group, member, Integer.toString(room), Long.toString(deadlineMs)));
                    args.addAll(fresh);
                    return redis.execute(CLAIM_KEY_SCRIPT, List.of(key), args)
                            .collectList()
                            .map(flat -> {
                                // A MULTI reply arrives either as the elements or as one list of them.
                                List<?> items = flat.size() == 1 && flat.get(0) instanceof List<?> l ? l : flat;
                                return parseClaimed(topic, items);
                            })
                            .flatMap(taken -> {
                                int left = room - taken.size();
                                if (left <= 0 || fresh.size() < room || round >= 4) return Mono.just(taken);
                                return claimKeyRound(key, topic, group, member, consumer, left, deadlineMs, round + 1)
                                        .map(more -> {
                                            List<Claimed> all = new ArrayList<>(taken);
                                            all.addAll(more);
                                            return all;
                                        });
                            });
                });
    }

    private static List<Claimed> parseClaimed(Topic topic, List<?> items) {
        List<Claimed> claimed = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            String id = items.get(i++).toString();
            int deliveries = Integer.parseInt(items.get(i++).toString());
            int n = Integer.parseInt(items.get(i++).toString());
            Map<String, String> fields = new java.util.LinkedHashMap<>();
            for (int f = 0; f < n; f += 2) {
                fields.put(items.get(i + f).toString(), items.get(i + f + 1).toString());
            }
            i += n;
            claimed.add(new Claimed(PushEventCodec.fromFields(id, topic, fields), deliveries));
        }
        return claimed;
    }

    @Override
    public Mono<Boolean> ackGroup(String group, Topic topic, String member, String id) {
        return redis.opsForStream().acknowledge(key(topic), group, id).map(n -> n > 0);
    }

    @Override
    public Mono<Long> releaseClaims(String group, Topic topic, String member) {
        return redis.execute(RELEASE_CLAIMS_SCRIPT, List.of(key(topic)), List.of(group, member, RELEASED_IDLE_MS))
                .next()
                .defaultIfEmpty(0L)
                .onErrorReturn(0L);
    }

    @Override
    public Flux<GroupStatus> groups(String prefix, int limit) {
        return redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(streamPrefix + "*")
                        .count(200)
                        .build())
                .sort()
                .concatMap(key -> redis.opsForStream()
                        .groups(key)
                        .onErrorResume(e -> Flux.empty())
                        .filter(g -> prefix == null
                                || prefix.isEmpty()
                                || g.groupName().startsWith(prefix))
                        .map(g -> new GroupStatus(
                                g.groupName(),
                                Topic.parse(key.substring(streamPrefix.length())),
                                g.pendingCount() == null ? 0 : g.pendingCount(),
                                g.consumerCount() == null ? 0 : g.consumerCount(),
                                g.lastDeliveredId())))
                .take(limit);
    }

    @Override
    public Mono<Long> dropGroup(String group) {
        return redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(streamPrefix + "*")
                        .count(200)
                        .build())
                .concatMap(key -> redis.opsForStream()
                        .groups(key)
                        .filter(g -> g.groupName().equals(group))
                        .next()
                        .flatMap(g ->
                                redis.opsForStream().destroyGroup(key, group).thenReturn(1L))
                        .onErrorReturn(0L))
                .reduce(0L, Long::sum);
    }

    @Override
    public int compareIds(String a, String b) {
        long[] x = parse(a);
        long[] y = parse(b);
        int c = Long.compare(x[0], y[0]);
        return c != 0 ? c : Long.compare(x[1], y[1]);
    }

    @Override
    public Mono<String> trimmedThrough(Topic topic) {
        if (cluster) return Mono.empty();
        return redis.execute(TRIMMED_THROUGH_SCRIPT, List.of(key(topic), trimKey(topic)), List.of())
                .next();
    }

    @Override
    public long historyLength(Topic topic) {
        Long n = redis.opsForStream().size(key(topic)).block();
        return n == null ? 0 : n;
    }

    @Override
    public Mono<Boolean> lease(String key, String holder, Duration ttl) {
        return redis.execute(LEASE_SCRIPT, List.of(leasePrefix + key), List.of(holder, Long.toString(ttl.toMillis())))
                .next()
                .map(n -> n != null && n > 0)
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Void> release(String key, String holder) {
        return redis.execute(RELEASE_SCRIPT, List.of(leasePrefix + key), List.of(holder))
                .then();
    }

    /**
     * ARGV: prefix, id, ttl ms, row json, principal ('' for none), instance, then the presence topics.
     * Replaces a row: the previous row's index entries are removed first, so a topic no longer listened
     * to leaves its set.
     */
    private static final String REGISTER = """
            local p = ARGV[1]
            local id = ARGV[2]
            local ttl = tonumber(ARGV[3])
            local row = p .. 'conn:' .. id
            local old = redis.call('GET', row)
            if old then
              local o = cjson.decode(old)
              if type(o.principal) == 'string' and o.principal ~= '' then redis.call('SREM', p .. 'user:' .. o.principal, id) end
              if type(o.instance) == 'string' then redis.call('SREM', p .. 'instance:' .. o.instance, id) end
              if type(o.presenceTopics) == 'table' then
                for _, t in ipairs(o.presenceTopics) do redis.call('SREM', p .. 'topic:' .. t, id) end
              end
            end
            redis.call('SET', row, ARGV[4], 'PX', ttl)
            local function index(k)
              redis.call('SADD', k, id)
              if redis.call('PTTL', k) < ttl * 2 then redis.call('PEXPIRE', k, ttl * 2) end
            end
            if ARGV[5] ~= '' then index(p .. 'user:' .. ARGV[5]) end
            index(p .. 'instance:' .. ARGV[6])
            for i = 7, #ARGV do index(p .. 'topic:' .. ARGV[i]) end
            return 1
            """;

    private static final RedisScript<Long> REGISTER_SCRIPT = new DefaultRedisScript<>(REGISTER, Long.class);
    /** ARGV: prefix, id. */
    private static final String UNREGISTER = """
            local p = ARGV[1]
            local id = ARGV[2]
            local row = p .. 'conn:' .. id
            local old = redis.call('GET', row)
            if not old then return 0 end
            local o = cjson.decode(old)
            if type(o.principal) == 'string' and o.principal ~= '' then redis.call('SREM', p .. 'user:' .. o.principal, id) end
            if type(o.instance) == 'string' then redis.call('SREM', p .. 'instance:' .. o.instance, id) end
            if type(o.presenceTopics) == 'table' then
              for _, t in ipairs(o.presenceTopics) do redis.call('SREM', p .. 'topic:' .. t, id) end
            end
            redis.call('DEL', row)
            return 1
            """;

    private static final RedisScript<Long> UNREGISTER_SCRIPT = new DefaultRedisScript<>(UNREGISTER, Long.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public boolean connectionRegistry() {
        return true;
    }

    @Override
    public Mono<Void> registerConnections(List<ConnectionRecord> records, Duration ttl) {
        long ms = Math.max(1, ttl.toMillis());
        return Flux.fromIterable(records)
                .concatMap(r -> {
                    List<String> args = new ArrayList<>();
                    args.add(keyPrefix);
                    args.add(r.id());
                    args.add(Long.toString(ms));
                    args.add(rowJson(r));
                    args.add(r.principal() == null ? "" : r.principal());
                    args.add(r.instance() == null ? "" : r.instance());
                    args.addAll(r.presenceTopics());
                    return redis.execute(REGISTER_SCRIPT, List.of(), args).then();
                })
                .then();
    }

    @Override
    public Mono<Void> unregisterConnection(String id) {
        return redis.execute(UNREGISTER_SCRIPT, List.of(), List.of(keyPrefix, id))
                .then();
    }

    @Override
    public Flux<ConnectionRecord> connections(String principal, int limit) {
        Flux<ConnectionRecord> rows = principal == null
                ? redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                                .match(connPrefix + "*")
                                .count(500)
                                .build())
                        .flatMap(k -> redis.opsForValue().get(k).map(RedisPushStore::parseRow))
                : liveMembers(keyPrefix + "user:" + principal);
        return rows.collectSortedList(ORDER)
                .flatMapMany(list -> Flux.fromStream(list.stream().limit(Math.max(0, limit))));
    }

    @Override
    public Mono<ConnectionRecord> connection(String id) {
        return redis.opsForValue().get(connPrefix + id).map(RedisPushStore::parseRow);
    }

    @Override
    public Flux<ConnectionRecord> connectionsOn(Topic topic, int offset, int limit) {
        return liveMembers(keyPrefix + "topic:" + topic)
                .collectSortedList(ORDER)
                .flatMapMany(list ->
                        Flux.fromStream(list.stream().skip(Math.max(0, offset)).limit(Math.max(0, limit))));
    }

    @Override
    public Mono<Long> countConnectionsOn(Topic topic) {
        return liveMembers(keyPrefix + "topic:" + topic).count();
    }

    private static final java.util.Comparator<ConnectionRecord> ORDER =
            java.util.Comparator.comparing(ConnectionRecord::since).thenComparing(ConnectionRecord::id);

    /** The rows of a set's members; a member whose row lapsed is removed from the set as it is met. */
    private Flux<ConnectionRecord> liveMembers(String setKey) {
        return redis.opsForSet()
                .members(setKey)
                .flatMap(
                        id -> redis.opsForValue()
                                .get(connPrefix + id)
                                .map(RedisPushStore::parseRow)
                                .switchIfEmpty(Mono.defer(() ->
                                        redis.opsForSet().remove(setKey, id).then(Mono.empty()))),
                        16);
    }

    private static String rowJson(ConnectionRecord r) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", r.id());
        row.put("instance", r.instance());
        row.put("principal", r.principal());
        row.put("transport", r.transport());
        row.put("deviceId", r.deviceId());
        row.put("since", r.since().toString());
        row.put("topics", r.topics());
        row.put("presenceTopics", r.presenceTopics());
        row.put("peerAddress", r.peerAddress());
        try {
            return JSON.writeValueAsString(row);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static ConnectionRecord parseRow(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(json);
            return new ConnectionRecord(
                    text(n, "id"),
                    text(n, "instance"),
                    text(n, "principal"),
                    text(n, "transport"),
                    text(n, "deviceId"),
                    Instant.parse(text(n, "since")),
                    list(n.get("topics")),
                    list(n.get("presenceTopics")),
                    text(n, "peerAddress"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bad connection row: " + json, e);
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode n, String field) {
        com.fasterxml.jackson.databind.JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> list(com.fasterxml.jackson.databind.JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null) n.forEach(e -> out.add(e.asText()));
        return List.copyOf(out);
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false);
    }

    private String key(Topic topic) {
        return streamPrefix + topic;
    }

    private String trimKey(Topic topic) {
        return trimPrefix + topic;
    }

    /** {@code millis-seq}; a plain number (the memory store's shape) reads as {@code millis-0}. */
    static long[] parse(String id) {
        try {
            int dash = id.indexOf('-');
            if (dash < 0) return new long[] {Long.parseLong(id), 0};
            return new long[] {Long.parseLong(id.substring(0, dash)), Long.parseLong(id.substring(dash + 1))};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an event id: '" + id + "'");
        }
    }

    private static Map<String, String> strings(Map<Object, Object> raw) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }
}
