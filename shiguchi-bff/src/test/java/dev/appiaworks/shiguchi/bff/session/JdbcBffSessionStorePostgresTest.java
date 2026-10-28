package dev.appiaworks.shiguchi.bff.session;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.postgresql.ds.PGSimpleDataSource;

@DisplayName("JdbcBffSessionStore on Postgres over pgjdbc")
class JdbcBffSessionStorePostgresTest extends JdbcBffSessionStoreTestBase {

    @Override
    DataSource newDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://localhost:" + EmbeddedPg.port() + "/postgres");
        ds.setUser("postgres");
        ds.setPassword("postgres");
        return ds;
    }

    @Override
    String name() {
        return "pg";
    }
}
