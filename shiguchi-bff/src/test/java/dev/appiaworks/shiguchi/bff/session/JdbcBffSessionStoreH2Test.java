package dev.appiaworks.shiguchi.bff.session;

import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcBffSessionStore on H2 (native mode)")
class JdbcBffSessionStoreH2Test extends JdbcBffSessionStoreTestBase {

    @Override
    DataSource newDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:bff_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    @Override
    String name() {
        return "h2";
    }
}
