package dev.appiaworks.shiguchi.cursus.jdbc;

import dev.appiaworks.protobuf.extras.api.ApiStatus;
import dev.appiaworks.shiguchi.cursus.PushProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Where the SQL of the JDBC engine differs between databases, in one place: the column types, the
 * paging clause, and how a "this already exists" failure looks. Everything else the engine runs is
 * portable SQL (single-row {@code UPDATE}/{@code INSERT}, no {@code MERGE}, no {@code RETURNING}),
 * so a dialect that misbehaves is fixed here. {@link #H2} and {@link #POSTGRES} are exercised by
 * the tests; the others are best effort.
 */
@ApiStatus(value = ApiStatus.Status.EXPERIMENTAL, since = "0.12.0")
public enum JdbcDialect {
    H2,
    POSTGRES,
    MYSQL,
    SQLSERVER,
    ORACLE,
    GENERIC;

    /** SQLStates that mean "that object already exists" across the common drivers. */
    private static final Set<String> EXISTS_STATES = Set.of("42S01", "42S11", "42P07", "42710", "X0Y32", "42P06");

    /** The dialect for a {@code DatabaseMetaData.getDatabaseProductName()}. */
    public static JdbcDialect fromProductName(String product) {
        String p = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (p.contains("h2")) return H2;
        if (p.contains("postgres")) return POSTGRES;
        if (p.contains("mysql") || p.contains("mariadb")) return MYSQL;
        if (p.contains("microsoft") || p.contains("sql server")) return SQLSERVER;
        if (p.contains("oracle")) return ORACLE;
        return GENERIC;
    }

    /** The dialect {@code config} forces, else the one the data source's database reports. */
    public static JdbcDialect resolve(DataSource dataSource, PushProperties.Jdbc config) {
        if (config.getDialect() != null) return config.getDialect();
        try (Connection c = dataSource.getConnection()) {
            return fromProductName(c.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "shiguchi.cursus.jdbc: cannot read the database product name to pick a dialect: " + e.getMessage(),
                    e);
        }
    }

    /** Short text (names, ids, keys): bounded so it can be indexed and part of a primary key. */
    public String string(int length) {
        return this == ORACLE ? "VARCHAR2(" + length + " CHAR)" : "VARCHAR(" + length + ")";
    }

    /** Unbounded text: payloads, attributes, JSON lists. */
    public String text() {
        return switch (this) {
            case H2 -> "CLOB";
            case MYSQL -> "LONGTEXT";
            case SQLSERVER -> "NVARCHAR(MAX)";
            case ORACLE -> "CLOB";
            default -> "TEXT";
        };
    }

    /** Binary payload of a typed event. */
    public String bytes() {
        return switch (this) {
            case POSTGRES -> "BYTEA";
            case MYSQL -> "LONGBLOB";
            case SQLSERVER -> "VARBINARY(MAX)";
            case ORACLE -> "BLOB";
            case H2 -> "BLOB";
            default -> "BLOB";
        };
    }

    /** 64-bit integer: ids and epoch milliseconds. */
    public String bigint() {
        return this == ORACLE ? "NUMBER(19)" : "BIGINT";
    }

    /** 0/1 flag; a portable stand-in for BOOLEAN. */
    public String flag() {
        return this == ORACLE ? "NUMBER(1)" : "SMALLINT";
    }

    /** The {@link Types} constant for a null binary parameter. */
    public int bytesSqlType() {
        return this == ORACLE ? Types.BLOB : Types.VARBINARY;
    }

    /** True when {@code CREATE TABLE IF NOT EXISTS} is understood. */
    public boolean tableIfNotExists() {
        return this == H2 || this == POSTGRES || this == MYSQL;
    }

    /** True when {@code CREATE INDEX IF NOT EXISTS} is understood. */
    public boolean indexIfNotExists() {
        return this == H2 || this == POSTGRES;
    }

    /** {@code FROM dual} where a {@code SELECT} without a table needs one, else empty. */
    public String fromDual() {
        return this == ORACLE || this == MYSQL ? " FROM dual" : "";
    }

    /**
     * The clause that takes {@code limit} rows after skipping {@code offset}, appended to a query
     * that has an {@code ORDER BY}.
     */
    public String page(int offset, int limit) {
        if (this == MYSQL) return " LIMIT " + limit + (offset > 0 ? " OFFSET " + offset : "");
        return " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /** True when {@code error} (or a cause) is a database saying the object being created already exists. */
    public boolean isAlreadyExists(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException e) {
                if (e.getSQLState() != null && EXISTS_STATES.contains(e.getSQLState())) return true;
                int code = e.getErrorCode();
                if (this == SQLSERVER && (code == 2714 || code == 1913)) return true;
                if (this == ORACLE && (code == 955 || code == 1408)) return true;
                if (this == MYSQL && code == 1061) return true;
            }
        }
        return false;
    }
}
