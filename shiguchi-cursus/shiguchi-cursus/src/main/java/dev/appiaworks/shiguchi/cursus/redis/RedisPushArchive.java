package dev.appiaworks.shiguchi.cursus.redis;

import dev.appiaworks.shiguchi.cursus.PushArchive;
import dev.appiaworks.shiguchi.cursus.PushEvent;
import dev.appiaworks.shiguchi.cursus.PushEventCodec;
import dev.appiaworks.shiguchi.cursus.Topic;
import dev.appiaworks.shiguchi.cursus.TopicPattern;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The archive of the {@code REDIS} engine, shared by every instance: a Stream per topic
 * ({@code <prefix>archive:<topic>}) holding the event under its own id, a sorted set of the topics
 * written ({@code <prefix>archive:topics}, scored by the last write) and a hash per topic of the
 * tombstones ({@code <prefix>archive:tomb:<topic>}).
 *
 * <p>Retention is enforced three ways: an append deletes the entries at the head of that topic's
 * stream that are older than {@code retention} (by their {@code time} field, so event ids that are
 * not timestamps still work) and prunes the topic index, every key expires {@code retention} after
 * its last write, and a read skips what is older anyway. A search reads at most {@code maxScan}
 * entries: it filters by id range, time, attributes and type, not by text.
 *
 * <p>A redacted event keeps its entry; the payload is masked on read from the tombstone hash, and
 * the entry is deleted from the live history stream so clients no longer resume into it.
 */
public final class RedisPushArchive implements PushArchive {

    private static final Logger log = LoggerFactory.getLogger(RedisPushArchive.class);

    private static final byte[] NULL = "null".getBytes(StandardCharsets.UTF_8);
    private static final Pattern STREAM_ID = Pattern.compile("\\d+-\\d+");
    private static final int PAGE = 500;
    private static final int TRIM_BATCH = 100;

    /**
     * KEYS: the archive stream, the topic index. ARGV: the event id, retention ms, now ms, the
     * topic, then the stream fields. Answers 1 when the id was not above the stream's newest and
     * the entry went in under a fresh id with the event id in {@code orig-id}.
     */
    private static final String APPEND = """
            local fallback = 0
            local ok, err = pcall(redis.call, 'XADD', KEYS[1], ARGV[1], unpack(ARGV, 5))
            if not ok then
              local msg = type(err) == 'table' and err.err or tostring(err)
              if not string.find(msg, 'equal or smaller', 1, true) then error(err) end
              local f = {'orig-id', ARGV[1]}
              for i = 5, #ARGV do f[#f + 1] = ARGV[i] end
              redis.call('XADD', KEYS[1], '*', unpack(f))
              fallback = 1
            end
            local cutoff = tonumber(ARGV[3]) - tonumber(ARGV[2])
            local head = redis.call('XRANGE', KEYS[1], '-', '+', 'COUNT', %d)
            for _, e in ipairs(head) do
              local t = nil
              local f = e[2]
              for i = 1, #f, 2 do if f[i] == 'time' then t = tonumber(f[i + 1]) end end
              if t ~= nil and t < cutoff then redis.call('XDEL', KEYS[1], e[1]) else break end
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', cutoff)
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return fallback
            """.formatted(TRIM_BATCH);

    private static final RedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>(APPEND, Long.class);

    /**
     * KEYS: the archive stream, the tombstone hash, the live history stream. ARGV: the id, the
     * tombstone value, retention ms. Answers 1 when this call redacted the event.
     */
    private static final String TOMBSTONE = """
            if #redis.call('XRANGE', KEYS[1], ARGV[1], ARGV[1]) == 0 then return 0 end
            if redis.call('HSETNX', KEYS[2], ARGV[1], ARGV[2]) == 0 then return 0 end
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            redis.call('XDEL', KEYS[3], ARGV[1])
            return 1
            """;

    private static final RedisScript<Long> TOMBSTONE_SCRIPT = new DefaultRedisScript<>(TOMBSTONE, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final String archivePrefix;
    private final String tombPrefix;
    private final String topicsKey;
    private final String liveStreamPrefix;
    private final Duration retention;
    private final int maxScan;
    private final AtomicBoolean fallbackLogged = new AtomicBoolean();

    public RedisPushArchive(ReactiveStringRedisTemplate redis, String keyPrefix, Duration retention, int maxScan) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("archive.retention must be above zero");
        }
        if (maxScan <= 0) throw new IllegalArgumentException("archive.max-scan must be above 0");
        this.redis = redis;
        this.archivePrefix = keyPrefix + "archive:stream:";
        this.tombPrefix = keyPrefix + "archive:tomb:";
        this.topicsKey = keyPrefix + "archive:topics";
        this.liveStreamPrefix = keyPrefix + "stream:";
        this.retention = retention;
        this.maxScan = maxScan;
    }

    @Override
    public Mono<Void> append(PushEvent event) {
        if (event.id() == null) return Mono.error(new IllegalArgumentException("an archived event needs its id"));
        List<String> args = new ArrayList<>();
        args.add(event.id());
        args.add(Long.toString(retention.toMillis()));
        args.add(Long.toString(System.currentTimeMillis()));
        args.add(event.topic().toString());
        PushEventCodec.toFields(event).forEach((k, v) -> {
            args.add(k);
            args.add(v);
        });
        return redis.execute(APPEND_SCRIPT, List.of(streamKey(event.topic()), topicsKey), args)
                .next()
                .doOnNext(fallback -> {
                    if (fallback != null && fallback == 1L && fallbackLogged.compareAndSet(false, true)) {
                        log.warn(
                                "Archive of {} got event id {} below its newest; kept under a new id with the"
                                        + " event id in orig-id (logged once)",
                                event.topic(),
                                event.id());
                    }
                })
                .then();
    }

    @Override
    public Flux<Entry> search(Query query) {
        TopicPattern pattern = query.topicPattern();
        Mono<List<Topic>> topics = query.exactTopic()
                ? Mono.just(List.of(Topic.parse(query.topic())))
                : redis.opsForZSet()
                        .scan(
                                topicsKey,
                                ScanOptions.scanOptions()
                                        .match(glob(query.topic()))
                                        .build())
                        .map(t -> Topic.parse(t.getValue()))
                        .filter(t -> pattern == null || pattern.matches(t))
                        .collectList();
        Instant cutoff = Instant.now().minus(retention);
        return topics.flatMapMany(list -> {
            int[] budget = {maxScan};
            return Flux.fromIterable(list)
                    .concatMap(topic -> scan(topic, query, cutoff, budget, null, new ArrayList<>()))
                    .concatMapIterable(found -> found)
                    .collectList()
                    .doOnNext(all -> {
                        if (budget[0] <= 0) {
                            log.debug("Archive search stopped after reading max-scan ({}) entries", maxScan);
                        }
                    })
                    .flatMapMany(all -> {
                        all.sort(Comparator.comparing((Entry e) -> e.event().id(), RedisPushArchive::compareIds)
                                .reversed());
                        return Flux.fromIterable(all.subList(0, Math.min(all.size(), query.limit())));
                    });
        });
    }

    @Override
    public Mono<Entry> get(Topic topic, String id) {
        if (id == null || !STREAM_ID.matcher(id).matches()) return Mono.empty();
        return redis.opsForStream()
                .range(streamKey(topic), Range.closed(id, id), Limit.limit().count(1))
                .next()
                .flatMap(record -> redis.opsForHash()
                        .get(tombKey(topic), id)
                        .map(Object::toString)
                        .map(tomb -> mask(event(topic, record), tomb))
                        .defaultIfEmpty(new Entry(event(topic, record), null, null)));
    }

    @Override
    public Mono<Boolean> tombstone(Topic topic, String id, String by) {
        if (id == null || !STREAM_ID.matcher(id).matches()) return Mono.just(false);
        String value = System.currentTimeMillis() + "|" + (by == null ? "" : by);
        return redis.execute(
                        TOMBSTONE_SCRIPT,
                        List.of(streamKey(topic), tombKey(topic), liveStreamPrefix + topic),
                        List.of(id, value, Long.toString(retention.toMillis())))
                .next()
                .map(n -> n != null && n == 1L)
                .defaultIfEmpty(false);
    }

    @Override
    public SearchCapabilities searchCapabilities() {
        return new SearchCapabilities(true, true, false, retention);
    }

    /**
     * Reads {@code topic}'s stream newest first, a page at a time, until {@code query.limit()}
     * matches are in {@code found} or the shared budget of entries to read is spent.
     */
    private Mono<List<Entry>> scan(
            Topic topic, Query query, Instant cutoff, int[] budget, String upper, List<Entry> found) {
        if (found.size() >= query.limit() || budget[0] <= 0) return Mono.just(found);
        int want = Math.min(PAGE, budget[0]);
        String floor = query.afterId();
        String ceiling = tighter(upper, query.beforeId());
        Range<String> range = Range.of(
                floor == null ? Range.Bound.unbounded() : Range.Bound.inclusive(floor),
                ceiling == null ? Range.Bound.unbounded() : Range.Bound.inclusive(ceiling));
        // a continuation re-reads the last id of the previous page, which is dropped below
        int ask = upper == null ? want : want + 1;
        return redis.opsForStream()
                .reverseRange(streamKey(topic), range, Limit.limit().count(ask))
                .collectList()
                .flatMap(records -> {
                    List<MapRecord<String, Object, Object>> page = records;
                    if (upper != null
                            && !page.isEmpty()
                            && upper.equals(page.get(0).getId().getValue())) {
                        page = page.subList(1, page.size());
                    }
                    budget[0] -= page.size();
                    List<Entry> candidates = new ArrayList<>();
                    for (MapRecord<String, Object, Object> record : page) {
                        PushEvent e = event(topic, record);
                        if (matches(e, query, cutoff)) candidates.add(new Entry(e, null, null));
                    }
                    String last = page.isEmpty()
                            ? null
                            : page.get(page.size() - 1).getId().getValue();
                    boolean more = records.size() >= ask && last != null;
                    return tombstones(topic, candidates).flatMap(masked -> {
                        for (Entry m : masked) {
                            if (found.size() < query.limit()) found.add(m);
                        }
                        return more ? scan(topic, query, cutoff, budget, last, found) : Mono.just(found);
                    });
                });
    }

    private Mono<List<Entry>> tombstones(Topic topic, List<Entry> entries) {
        if (entries.isEmpty()) return Mono.just(entries);
        List<Object> ids = entries.stream().map(e -> (Object) e.event().id()).toList();
        return redis.opsForHash().multiGet(tombKey(topic), ids).map(values -> {
            Map<String, String> tombs = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                if (values.get(i) != null)
                    tombs.put((String) ids.get(i), values.get(i).toString());
            }
            if (tombs.isEmpty()) return entries;
            List<Entry> out = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                String tomb = tombs.get(e.event().id());
                out.add(tomb == null ? e : mask(e.event(), tomb));
            }
            return out;
        });
    }

    private static Entry mask(PushEvent e, String tomb) {
        int bar = tomb.indexOf('|');
        long at = Long.parseLong(bar < 0 ? tomb : tomb.substring(0, bar));
        String by = bar < 0 || bar == tomb.length() - 1 ? null : tomb.substring(bar + 1);
        PushEvent redacted = new PushEvent(e.topic(), e.id(), NULL, e.attributes(), e.key(), e.time());
        return new Entry(redacted, Instant.ofEpochMilli(at), by);
    }

    private static boolean matches(PushEvent e, Query q, Instant cutoff) {
        if (e.time() != null) {
            if (e.time().isBefore(cutoff)) return false;
            if (q.from() != null && e.time().isBefore(q.from())) return false;
            if (q.to() != null && !e.time().isBefore(q.to())) return false;
        } else if (q.from() != null || q.to() != null) {
            return false;
        }
        if (q.afterId() != null && compareIds(e.id(), q.afterId()) <= 0) return false;
        if (q.beforeId() != null && compareIds(e.id(), q.beforeId()) >= 0) return false;
        for (Map.Entry<String, String> a : q.attributes().entrySet()) {
            if (!a.getValue().equals(e.attributes().get(a.getKey()))) return false;
        }
        if (q.typeUrl() != null) {
            if ("json".equals(q.typeUrl()) ? e.typed() : !q.typeUrl().equals(e.typeUrl())) return false;
        }
        return true;
    }

    private PushEvent event(Topic topic, MapRecord<String, Object, Object> record) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        String original = fields.remove("orig-id");
        return PushEventCodec.fromFields(
                original != null ? original : record.getId().getValue(), topic, fields);
    }

    /** The smaller of two optional ids (the tighter upper bound); null when both are. */
    private static String tighter(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return compareIds(a, b) <= 0 ? a : b;
    }

    static int compareIds(String a, String b) {
        long[] x = parts(a);
        long[] y = parts(b);
        int c = Long.compare(x[0], y[0]);
        return c != 0 ? c : Long.compare(x[1], y[1]);
    }

    private static long[] parts(String id) {
        int dash = id.indexOf('-');
        try {
            if (dash < 0) return new long[] {Long.parseLong(id), 0};
            return new long[] {Long.parseLong(id.substring(0, dash)), Long.parseLong(id.substring(dash + 1))};
        } catch (NumberFormatException ex) {
            return new long[] {0, 0};
        }
    }

    /** A conservative SCAN glob: the literal prefix of the topic filter, escaped, then {@code *}. */
    private static String glob(String filter) {
        if (filter == null) return "*";
        String text = filter.indexOf(':') < 0 ? filter + ":" : filter;
        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '{') {
                cut = i;
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        for (char c : text.substring(0, cut).toCharArray()) {
            if (c == '\\' || c == '*' || c == '?' || c == '[' || c == ']') out.append('\\');
            out.append(c);
        }
        return out.append('*').toString();
    }

    private String streamKey(Topic topic) {
        return archivePrefix + topic;
    }

    private String tombKey(Topic topic) {
        return tombPrefix + topic;
    }
}
