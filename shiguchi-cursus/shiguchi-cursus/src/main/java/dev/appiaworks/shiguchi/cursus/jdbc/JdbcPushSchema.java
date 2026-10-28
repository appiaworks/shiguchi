package dev.appiaworks.shiguchi.cursus.jdbc;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The tables the JDBC engine uses, and the statements that create them, per {@link JdbcDialect}.
 * Run at startup unless {@code cursus.jdbc.create-schema} is off, in which case the same statements
 * are what a database administrator applies. Where the database has no {@code IF NOT EXISTS}
 * the statement is plain and the runner ignores the "already exists" error. Every time is epoch
 * milliseconds in a {@code BIGINT}, never a database timestamp (no time zone to drift between
 * dialects).
 *
 * <pre>
 * &lt;t&gt;             the events: id, topic, data (JSON text), attributes (JSON text), msg_key, event_time,
 *                 expires_at, type_url, proto, in_history, archived_until, tombstoned_at, tombstoned_by
 * &lt;t&gt;_seq         one row, the last id handed out: ids are minted under its row lock, so they commit in order
 * &lt;t&gt;_lease       ownership leases of the upstream-backed topics
 * &lt;t&gt;_delivery    the delivery log
 * &lt;t&gt;_session     persistent session cursors (consumer groups keep theirs here as "group:&lt;name&gt;")
 * &lt;t&gt;_group       consumer-group claims
 * &lt;t&gt;_connection  the cluster-wide connection registry
 * &lt;t&gt;_trim        per topic, the highest id the history dropped and the sweep then deleted (the gap signal's watermark)
 * </pre>
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.14.0")
public final class JdbcPushSchema {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private JdbcPushSchema() {}

    /** The table name, checked to be a plain (optionally schema-qualified) identifier: it is spliced into SQL. */
    public static String table(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalStateException(
                    "shiguchi.cursus.jdbc.table must be a plain identifier, got '" + name + "'");
        }
        return name;
    }

    public static String sequenceTable(String table) {
        return table(table) + "_seq";
    }

    public static String leaseTable(String table) {
        return table(table) + "_lease";
    }

    public static String deliveryTable(String table) {
        return table(table) + "_delivery";
    }

    public static String sessionTable(String table) {
        return table(table) + "_session";
    }

    public static String groupTable(String table) {
        return table(table) + "_group";
    }

    public static String connectionTable(String table) {
        return table(table) + "_connection";
    }

    public static String trimTable(String table) {
        return table(table) + "_trim";
    }

    /** The statements, in order, without the archive index. */
    public static List<String> statements(JdbcDialect dialect, String table) {
        return statements(dialect, table, false);
    }

    /** The statements, in order. With {@code archive} the time index the search uses is added. */
    public static List<String> statements(JdbcDialect d, String table, boolean archive) {
        String t = table(table);
        String index = t.replace('.', '_');
        String createTable = d.tableIfNotExists() ? "CREATE TABLE IF NOT EXISTS " : "CREATE TABLE ";
        String createIndex = d.indexIfNotExists() ? "CREATE INDEX IF NOT EXISTS " : "CREATE INDEX ";
        String name = d.string(255);
        String big = d.bigint();
        List<String> out = new ArrayList<>(List.of(
                createTable + t + " ("
                        + "id " + big + " PRIMARY KEY, "
                        + "topic " + name + " NOT NULL, "
                        + "data " + d.text() + " NOT NULL, "
                        + "attributes " + d.text() + ", "
                        + "msg_key " + name + ", "
                        + "event_time " + big + " NOT NULL, "
                        + "expires_at " + big + ", "
                        + "type_url " + name + ", "
                        + "proto " + d.bytes() + ", "
                        + "in_history " + d.flag() + " NOT NULL, "
                        + "archived_until " + big + ", "
                        + "tombstoned_at " + big + ", "
                        + "tombstoned_by " + name + ")",
                createIndex + index + "_topic_id ON " + t + " (topic, id)",
                createIndex + index + "_expires ON " + t + " (expires_at)",
                createTable + sequenceTable(t) + " (id INTEGER PRIMARY KEY, last_id " + big + " NOT NULL)",
                "INSERT INTO " + sequenceTable(t) + " (id, last_id) SELECT 1, 0" + d.fromDual()
                        + " WHERE NOT EXISTS (SELECT 1 FROM " + sequenceTable(t) + " WHERE id = 1)",
                createTable + leaseTable(t) + " ("
                        + "lease_key " + name + " PRIMARY KEY, "
                        + "holder " + name + " NOT NULL, "
                        + "expires_at " + big + " NOT NULL)",
                createTable + deliveryTable(t) + " ("
                        + "topic " + name + " NOT NULL, "
                        + "event_id " + big + " NOT NULL, "
                        + "connection_id " + name + " NOT NULL, "
                        + "principal " + name + ", "
                        + "transport " + d.string(64) + " NOT NULL, "
                        + "delivered_at " + big + " NOT NULL, "
                        + "outcome " + d.string(64) + " NOT NULL)",
                createIndex + index + "_delivery_event ON " + deliveryTable(t) + " (topic, event_id)",
                createTable + sessionTable(t) + " ("
                        + "session_key " + name + " NOT NULL, "
                        + "topic " + name + " NOT NULL, "
                        + "last_ack_id " + big + " NOT NULL, "
                        + "expires_at " + big + " NOT NULL, "
                        + "PRIMARY KEY (session_key, topic))",
                createTable + groupTable(t) + " ("
                        + "group_name " + name + " NOT NULL, "
                        + "topic " + name + " NOT NULL, "
                        + "event_id " + big + " NOT NULL, "
                        + "claimed_by " + name + " NOT NULL, "
                        + "claimed_at " + big + " NOT NULL, "
                        + "deliveries INTEGER NOT NULL, "
                        + "msg_key " + name + ", "
                        + "PRIMARY KEY (group_name, topic, event_id))",
                createTable + connectionTable(t) + " ("
                        + "id " + name + " PRIMARY KEY, "
                        + "instance_id " + name + ", "
                        + "principal " + name + ", "
                        + "transport " + d.string(64) + ", "
                        + "device_id " + name + ", "
                        + "since_at " + big + " NOT NULL, "
                        + "topics " + d.text() + " NOT NULL, "
                        + "presence_topics " + d.text() + " NOT NULL, "
                        + "expires_at " + big + " NOT NULL, "
                        + "peer_address " + name + ")",
                createIndex + index + "_connection_principal ON " + connectionTable(t) + " (principal)",
                createTable + trimTable(t) + " ("
                        + "topic " + name + " PRIMARY KEY, "
                        + "trimmed_through " + big + " NOT NULL, "
                        + "updated_at " + big + " NOT NULL)"));
        if (archive) out.add(createIndex + index + "_time ON " + t + " (event_time)");
        return out;
    }
}
