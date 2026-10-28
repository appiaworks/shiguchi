package dev.appiaworks.shiguchi.cursus.jdbc;

import dev.appiaworks.shiguchi.cursus.postgres.EmbeddedPg;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The databases the JDBC engine tests run on: H2 in its native mode (not {@code MODE=PostgreSQL},
 * which would hide dialect bugs) and the embedded Postgres through pgjdbc. Each test class passes
 * its own name, so classes never share tables (Postgres) or a database (H2).
 */
enum JdbcTestDatabase {
    H2("h2") {
        @Override
        DataSource dataSource(String name) {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:push_" + name + ";DB_CLOSE_DELAY=-1");
            return ds;
        }
    },
    POSTGRES("pg") {
        @Override
        DataSource dataSource(String name) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl("jdbc:postgresql://localhost:" + EmbeddedPg.port() + "/postgres");
            ds.setUser("postgres");
            ds.setPassword("postgres");
            return ds;
        }
    };

    private final String suffix;

    JdbcTestDatabase(String suffix) {
        this.suffix = suffix;
    }

    abstract DataSource dataSource(String name);

    /** A table name that is unique per test class and database: {@code push_event_store_test} gives {@code ..._pg}. */
    String table(String base) {
        return base + "_" + suffix;
    }
}
