package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore KEY ordering and seek on Postgres over pgjdbc")
class JdbcPushStoreOrderingPostgresTest extends JdbcPushStoreOrderingTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
