package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Runs a subscription for as long as it is wanted: reconnects with backoff and jitter, resumes
 * from the last id seen, prints {@code reset} and honours {@code overflow}, and refreshes the token once
 * when the server says it expired.
 */
final class Subscriber implements Transport.EventSink {

    /** Tuning; the defaults are what the command line uses. */
    record Options(long maxEvents, int retries, long backoffBaseMs, long backoffCapMs) {
        static Options defaults() {
            return new Options(0, -1, 500, 30_000);
        }
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final Transport transport;
    private final TokenSource tokens;
    private final Out out;
    private final Lifecycle lifecycle;
    private final Options options;
    private final Random random = new Random();

    private long count;
    private String lastId;
    private final Map<String, String> lastByTopic = new HashMap<>();

    Subscriber(Transport transport, TokenSource tokens, Out out, Lifecycle lifecycle, Options options) {
        this.transport = transport;
        this.tokens = tokens;
        this.out = out;
        this.lifecycle = lifecycle;
        this.options = options;
    }

    long count() {
        return count;
    }

    /** The delay before reconnect number {@code attempt} (1 for the first): exponential, capped, jittered. */
    static long delay(int attempt, Options options, Random random) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        long ceiling = Math.min(options.backoffCapMs(), options.backoffBaseMs() << exponent);
        long floor = ceiling / 2;
        return floor + (ceiling - floor <= 0 ? 0 : (long) (random.nextDouble() * (ceiling - floor + 1)));
    }

    /** Subscribes until stopped, the event limit is reached or the retries run out. */
    int run(Transport.SubRequest initial) {
        int attempt = 0;
        boolean refreshed = false;
        String lastError = null;
        while (!lifecycle.isStopped()) {
            long before = count;
            Transport.SubRequest request =
                    lastId == null ? initial : initial.resumeFrom(lastId, new HashMap<>(lastByTopic));
            try {
                Transport.End end = transport.stream(request, this, lifecycle);
                refreshed = false;
                if (end.kind() == Transport.End.Kind.DONE || limitReached()) return 0;
                if (lifecycle.isStopped()) break;
                if (end.kind() == Transport.End.Kind.RESET) {
                    lastId = null;
                    lastByTopic.clear();
                }
                out.info("stream ended (" + end.kind().name().toLowerCase()
                        + (end.reason() == null ? "" : ": " + end.reason()) + "), reconnecting");
                attempt = count > before ? 1 : attempt + 1;
                lastError = "the stream keeps ending";
            } catch (CliException e) {
                if (lifecycle.isStopped()) break;
                if (e.unauthorized() && tokens.canRefresh() && !refreshed) {
                    refreshed = true;
                    tokens.refresh();
                    out.info("token rejected, refreshed with --token-cmd");
                    continue;
                }
                if (!e.retryable()) throw e;
                attempt++;
                lastError = e.getMessage();
                out.info(e.getMessage() + ", reconnecting");
            } catch (Exception e) {
                if (lifecycle.isStopped()) break;
                attempt++;
                lastError = Http.describe(e);
                out.info(lastError + ", reconnecting");
            }
            if (options.retries() >= 0 && attempt > options.retries()) {
                throw new CliException(
                        CliException.TRANSPORT, "giving up after " + options.retries() + " reconnects: " + lastError);
            }
            if (!lifecycle.sleep(delay(attempt, options, random))) break;
        }
        return 0;
    }

    private boolean limitReached() {
        return options.maxEvents() > 0 && count >= options.maxEvents();
    }

    @Override
    public boolean event(ObjectNode event) {
        count++;
        String id = event.path("id").asText(null);
        if (id != null && !id.isEmpty()) {
            lastId = id;
            lastByTopic.put(event.path("topic").asText(), id);
        }
        if (out.pretty()) {
            out.text(LocalTime.now().format(CLOCK) + "  " + event.path("topic").asText() + "  " + id + "  "
                    + event.path("data").toString());
        } else {
            out.json(event);
        }
        return !limitReached();
    }

    @Override
    public void control(String kind, JsonNode body) {
        ObjectNode line = Http.JSON.createObjectNode();
        line.set(kind, body == null ? Http.JSON.createObjectNode() : body);
        out.json(line);
    }
}
