package dev.appiaworks.shiguchi.cursus.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one table the POSTGRES engine uses, and the statements that create it. Idempotent
 * ({@code IF NOT EXISTS}), run at startup unless {@code cursus.postgres.create-schema} is off, in
 * which case the same statements are what a database administrator applies.
 *
 * <pre>
 * id             BIGSERIAL PRIMARY KEY   -- the event id, ordered across every instance
 * topic          TEXT NOT NULL           -- namespace:name
 * data           TEXT NOT NULL           -- the JSON payload, byte for byte as published ('null' once tombstoned)
 * attributes     JSONB                   -- publisher-set metadata
 * key            TEXT                    -- ordering key
 * time           TIMESTAMPTZ NOT NULL    -- when the gateway accepted it
 * expires_at     TIMESTAMPTZ             -- time + history-ttl; out of the history when past
 * type_url       TEXT                    -- the typed payload's type, when there is one
 * proto          BYTEA                   -- the typed payload's bytes
 * history        BOOLEAN NOT NULL        -- still part of the topic's history (false once trimmed, or never for a topic without one)
 * archived_until TIMESTAMPTZ             -- time + archive.retention; null when the archive is off
 * tombstoned_at  TIMESTAMPTZ             -- when an operator redacted it
 * tombstoned_by  TEXT                    -- who did
 * </pre>
 *
 * <p>A second table, {@code <table>_lease (key, holder, expires_at)}, holds the ownership leases of
 * the upstream-backed topics.
 *
 * <p>A third, {@code <table>_connection}, is the cluster-wide connection registry: one row per live
 * connection, rewritten by every keepalive tick with a fresh {@code expires_at}.
 *
 * <p>A fourth, {@code <table>_trim (topic, trimmed_through, updated_at)}, is the gap signal's watermark: per
 * topic, the highest id the history dropped and the sweep then deleted (rows still present are read
 * from the table itself).
 *
 * <p>The table doubles as the archive: a row leaves the history when it is trimmed or expires
 * and is deleted once it is out of both windows. The archive's three indexes (time, a GIN over
 * the attributes, a GIN over a {@code tsvector} of the payload) are created only when the archive
 * is on, since each costs something per insert.
 */
public final class PostgresPushSchema {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private PostgresPushSchema() {}

    /** The table name, checked to be a plain (optionally schema-qualified) identifier: it is spliced into SQL. */
    public static String table(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalStateException(
                    "shiguchi.cursus.postgres.table must be a plain identifier, got '" + name + "'");
        }
        return name;
    }

    public static String channel(String name) {
        if (name == null || !Pattern.matches("[A-Za-z_][A-Za-z0-9_]*", name)) {
            throw new IllegalStateException(
                    "shiguchi.cursus.postgres.channel must be a plain identifier, got '" + name + "'");
        }
        return name;
    }

    /** The consumer-group claims next to the event table: {@code push_event} gives {@code push_event_group}. */
    public static String groupTable(String table) {
        return table(table) + "_group";
    }

    /** The session cursors next to the event table: {@code push_event} gives {@code push_event_session}. */
    public static String sessionTable(String table) {
        return table(table) + "_session";
    }

    /** The delivery log next to the event table: {@code push_event} gives {@code push_event_delivery}. */
    public static String deliveryTable(String table) {
        return table(table) + "_delivery";
    }

    /** The connection registry next to the event table: {@code push_event} gives {@code push_event_connection}. */
    public static String connectionTable(String table) {
        return table(table) + "_connection";
    }

    /** The trim watermarks next to the event table: {@code push_event} gives {@code push_event_trim}. */
    public static String trimTable(String table) {
        return table(table) + "_trim";
    }

    /** The lease table next to the event table: {@code push_event} gives {@code push_event_lease}. */
    public static String leaseTable(String table) {
        return table(table) + "_lease";
    }

    /** The statements, in order; each is idempotent. Same as {@link #statements(String, boolean)} without the archive. */
    public static List<String> statements(String table) {
        return statements(table, false);
    }

    /**
     * The statements, in order; each is idempotent. The {@code ALTER TABLE}s bring a table created
     * by an earlier release up to date. With {@code archive} the search indexes are added.
     */
    public static List<String> statements(String table, boolean archive) {
        String t = table(table);
        String index = t.replace('.', '_');
        List<String> out = new ArrayList<>(List.of(
                "CREATE TABLE IF NOT EXISTS " + t + " ("
                        + "id BIGSERIAL PRIMARY KEY, "
                        + "topic TEXT NOT NULL, "
                        + "data TEXT NOT NULL, "
                        + "attributes JSONB, "
                        + "key TEXT, "
                        + "time TIMESTAMPTZ NOT NULL, "
                        + "expires_at TIMESTAMPTZ, "
                        + "type_url TEXT, "
                        + "proto BYTEA, "
                        + "history BOOLEAN NOT NULL DEFAULT TRUE, "
                        + "archived_until TIMESTAMPTZ, "
                        + "tombstoned_at TIMESTAMPTZ, "
                        + "tombstoned_by TEXT)",
                "ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS history BOOLEAN NOT NULL DEFAULT TRUE",
                "ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS archived_until TIMESTAMPTZ",
                "ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS tombstoned_at TIMESTAMPTZ",
                "ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS tombstoned_by TEXT",
                "CREATE INDEX IF NOT EXISTS " + index + "_topic_id ON " + t + " (topic, id)",
                "CREATE INDEX IF NOT EXISTS " + index + "_expires ON " + t
                        + " (expires_at) WHERE expires_at IS NOT NULL",
                "CREATE TABLE IF NOT EXISTS " + leaseTable(t) + " ("
                        + "key TEXT PRIMARY KEY, "
                        + "holder TEXT NOT NULL, "
                        + "expires_at TIMESTAMPTZ NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + deliveryTable(t) + " ("
                        + "topic TEXT NOT NULL, "
                        + "event_id BIGINT NOT NULL, "
                        + "connection_id TEXT NOT NULL, "
                        + "principal TEXT, "
                        + "transport TEXT NOT NULL, "
                        + "time TIMESTAMPTZ NOT NULL, "
                        + "outcome TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + index + "_delivery_event ON " + deliveryTable(t) + " (topic, event_id)",
                "CREATE TABLE IF NOT EXISTS " + sessionTable(t) + " ("
                        + "session TEXT NOT NULL, "
                        + "topic TEXT NOT NULL, "
                        + "last_ack_id BIGINT NOT NULL, "
                        + "expires_at TIMESTAMPTZ NOT NULL, "
                        + "PRIMARY KEY (session, topic))",
                "CREATE TABLE IF NOT EXISTS " + groupTable(t) + " ("
                        + "group_name TEXT NOT NULL, "
                        + "topic TEXT NOT NULL, "
                        + "event_id BIGINT NOT NULL, "
                        + "member TEXT NOT NULL, "
                        + "claimed_at TIMESTAMPTZ NOT NULL, "
                        + "deliveries INT NOT NULL DEFAULT 1, "
                        + "PRIMARY KEY (group_name, topic, event_id))",
                "ALTER TABLE " + groupTable(t) + " ADD COLUMN IF NOT EXISTS key TEXT",
                "CREATE TABLE IF NOT EXISTS " + connectionTable(t) + " ("
                        + "id TEXT PRIMARY KEY, "
                        + "instance TEXT, "
                        + "principal TEXT, "
                        + "transport TEXT, "
                        + "device_id TEXT, "
                        + "since TIMESTAMPTZ NOT NULL, "
                        + "topics JSONB NOT NULL DEFAULT '[]', "
                        + "presence_topics JSONB NOT NULL DEFAULT '[]', "
                        + "expires_at TIMESTAMPTZ NOT NULL)",
                "ALTER TABLE " + connectionTable(t) + " ADD COLUMN IF NOT EXISTS peer_address TEXT",
                "CREATE INDEX IF NOT EXISTS " + index + "_connection_principal ON " + connectionTable(t)
                        + " (principal)",
                "CREATE INDEX IF NOT EXISTS " + index + "_connection_presence ON " + connectionTable(t)
                        + " USING GIN (presence_topics)",
                "CREATE TABLE IF NOT EXISTS " + trimTable(t) + " ("
                        + "topic TEXT PRIMARY KEY, "
                        + "trimmed_through BIGINT NOT NULL, "
                        + "updated_at TIMESTAMPTZ NOT NULL DEFAULT now())"));
        if (archive) {
            out.add("CREATE INDEX IF NOT EXISTS " + index + "_time ON " + t + " (time)");
            out.add("CREATE INDEX IF NOT EXISTS " + index + "_attributes ON " + t + " USING GIN (attributes)");
            out.add("CREATE INDEX IF NOT EXISTS " + index + "_text ON " + t
                    + " USING GIN (to_tsvector('simple', data))");
        }
        return out;
    }
}
