package dev.appiaworks.shiguchi.bff.session.jdbc;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * The three tables the JDBC session store uses and the statements that create them, per
 * {@link Dialect}. Run at startup unless {@code shiguchi.bff.session.jdbc.create-schema} is
 * off, in which case these are the statements a database administrator applies. Every time is
 * epoch milliseconds in a {@code BIGINT}, never a database timestamp.
 *
 * <pre>
 * &lt;p&gt;session  one row per session: session_key (primary key), subject, provider, application, sid,
 *               name, email, picture, access_token, access_token_expires_at, refresh_token, id_token,
 *               created, last_seen, version, expires_at, lease_owner, lease_until (the refresh lease),
 *               acr, amr (space-separated), auth_time (the authentication context),
 *               claims (JSON), user_agent (the session claims and the sign-in's browser)
 * &lt;p&gt;login    one row per sign-in in flight: state (primary key), code_verifier, nonce, provider,
 *               application, return_to, redirect_uri, created, expires_at, max_age
 * </pre>
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.13.0")
public final class JdbcBffSchema {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    /** SQLStates that mean "that object already exists" across the common drivers. */
    private static final Set<String> EXISTS_STATES = Set.of(
            "42S01",
            "42S11",
            "42P07",
            "42710",
            "X0Y32",
            "42P06",
            // a column that already exists: MySQL/MariaDB, PostgreSQL, H2
            "42S21",
            "42701",
            "42121");

    private JdbcBffSchema() {}

    /** Where the SQL differs between databases: column types and the "already exists" failure. */
    public enum Dialect {
        H2,
        POSTGRES,
        MYSQL,
        SQLSERVER,
        ORACLE,
        GENERIC;

        /** The dialect for a {@code DatabaseMetaData.getDatabaseProductName()}. */
        public static Dialect fromProductName(String product) {
            String p = product == null ? "" : product.toLowerCase(Locale.ROOT);
            if (p.contains("h2")) return H2;
            if (p.contains("postgres")) return POSTGRES;
            if (p.contains("mysql") || p.contains("mariadb")) return MYSQL;
            if (p.contains("microsoft") || p.contains("sql server")) return SQLSERVER;
            if (p.contains("oracle")) return ORACLE;
            return GENERIC;
        }

        /** The dialect {@code forced} names, else the one the data source's database reports. */
        public static Dialect resolve(DataSource dataSource, Dialect forced) {
            if (forced != null) return forced;
            try (Connection c = dataSource.getConnection()) {
                return fromProductName(c.getMetaData().getDatabaseProductName());
            } catch (SQLException e) {
                throw new IllegalStateException(
                        "shiguchi.bff.session.jdbc: cannot read the database product name to pick a dialect: "
                                + e.getMessage(),
                        e);
            }
        }

        /** Short bounded text, usable in a primary key. */
        String string(int length) {
            return this == ORACLE ? "VARCHAR2(" + length + " CHAR)" : "VARCHAR(" + length + ")";
        }

        /** Unbounded text: tokens and URLs. */
        String text() {
            return switch (this) {
                case H2, ORACLE -> "CLOB";
                case MYSQL -> "LONGTEXT";
                case SQLSERVER -> "NVARCHAR(MAX)";
                default -> "TEXT";
            };
        }

        /** 64-bit integer: epoch milliseconds and the version. */
        String bigint() {
            return this == ORACLE ? "NUMBER(19)" : "BIGINT";
        }

        boolean tableIfNotExists() {
            return this == H2 || this == POSTGRES || this == MYSQL;
        }

        /**
         * The clause adding a column: {@code IF NOT EXISTS} where the database has it (the others
         * fail with "already exists", which the store ignores), no {@code COLUMN} keyword for SQL
         * Server and Oracle.
         */
        String addColumn() {
            return switch (this) {
                case SQLSERVER, ORACLE -> "ADD ";
                case H2, POSTGRES -> "ADD COLUMN IF NOT EXISTS ";
                default -> "ADD COLUMN ";
            };
        }

        boolean indexIfNotExists() {
            return this == H2 || this == POSTGRES;
        }

        /** True when {@code error} (or a cause) is a database saying the object being created already exists. */
        public boolean isAlreadyExists(Throwable error) {
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t instanceof SQLException e) {
                    if (e.getSQLState() != null && EXISTS_STATES.contains(e.getSQLState())) return true;
                    int code = e.getErrorCode();
                    if (this == SQLSERVER && (code == 2714 || code == 1913 || code == 2705)) return true;
                    if (this == ORACLE && (code == 955 || code == 1408 || code == 1430)) return true;
                    if (this == MYSQL && (code == 1061 || code == 1060)) return true;
                }
            }
            return false;
        }
    }

    /** A table name, checked to be a plain (optionally schema-qualified) identifier: it is spliced into SQL. */
    static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalStateException(
                    "shiguchi.bff.session.jdbc.table-prefix must give plain table names, got '" + name + "'");
        }
        return name;
    }

    public static String sessionTable(String prefix) {
        return identifier(prefix + "session");
    }

    public static String loginTable(String prefix) {
        return identifier(prefix + "login");
    }

    public static String jtiTable(String prefix) {
        return identifier(prefix + "jti");
    }

    /**
     * The statements, in order: the session and login tables and their expiry indexes, then the
     * columns added after the first release of the tables ({@code lease_owner}, {@code lease_until}:
     * the refresh lease; {@code acr}, {@code amr}, {@code auth_time}: the authentication context;
     * {@code max_age} on the login), then the logout-token table and the session indexes by subject and by
     * {@code sid} (back-channel logout), then the authentication-context columns, each idempotent, so a table made by an earlier version is
     * brought up to date.
     */
    public static List<String> statements(Dialect d, String prefix) {
        String session = sessionTable(prefix);
        String login = loginTable(prefix);
        String jti = jtiTable(prefix);
        String createTable = d.tableIfNotExists() ? "CREATE TABLE IF NOT EXISTS " : "CREATE TABLE ";
        String createIndex = d.indexIfNotExists() ? "CREATE INDEX IF NOT EXISTS " : "CREATE INDEX ";
        String big = d.bigint();
        String text = d.text();
        return List.of(
                createTable + session + " ("
                        + "session_key " + d.string(64) + " PRIMARY KEY, "
                        + "subject " + d.string(255) + ", "
                        + "provider " + d.string(64) + ", "
                        + "application " + d.string(64) + ", "
                        + "sid " + d.string(255) + ", "
                        + "name " + d.string(255) + ", "
                        + "email " + d.string(255) + ", "
                        + "picture " + text + ", "
                        + "access_token " + text + ", "
                        + "access_token_expires_at " + big + ", "
                        + "refresh_token " + text + ", "
                        + "id_token " + text + ", "
                        + "created " + big + " NOT NULL, "
                        + "last_seen " + big + " NOT NULL, "
                        + "version " + big + " NOT NULL, "
                        + "expires_at " + big + " NOT NULL)",
                createIndex + session.replace('.', '_') + "_expires ON " + session + " (expires_at)",
                createTable + login + " ("
                        + "state " + d.string(128) + " PRIMARY KEY, "
                        + "code_verifier " + d.string(255) + ", "
                        + "nonce " + d.string(255) + ", "
                        + "provider " + d.string(64) + ", "
                        + "application " + d.string(64) + ", "
                        + "return_to " + text + ", "
                        + "redirect_uri " + text + ", "
                        + "created " + big + " NOT NULL, "
                        + "expires_at " + big + " NOT NULL)",
                createIndex + login.replace('.', '_') + "_expires ON " + login + " (expires_at)",
                "ALTER TABLE " + session + " " + d.addColumn() + "lease_owner " + d.string(64),
                "ALTER TABLE " + session + " " + d.addColumn() + "lease_until " + big,
                createTable + jti + " ("
                        + "jti_key " + d.string(400) + " PRIMARY KEY, "
                        + "expires_at " + big + " NOT NULL)",
                createIndex + jti.replace('.', '_') + "_expires ON " + jti + " (expires_at)",
                createIndex + session.replace('.', '_') + "_subject ON " + session + " (provider, subject)",
                createIndex + session.replace('.', '_') + "_sid ON " + session + " (provider, sid)",
                "ALTER TABLE " + session + " " + d.addColumn() + "acr " + d.string(255),
                "ALTER TABLE " + session + " " + d.addColumn() + "amr " + d.string(255),
                "ALTER TABLE " + session + " " + d.addColumn() + "auth_time " + big,
                "ALTER TABLE " + login + " " + d.addColumn() + "max_age " + big,
                "ALTER TABLE " + session + " " + d.addColumn() + "claims " + d.string(4096),
                "ALTER TABLE " + session + " " + d.addColumn() + "user_agent " + d.string(255));
    }
}
