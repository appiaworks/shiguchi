package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore on Postgres over pgjdbc")
class JdbcPushStorePostgresTest extends JdbcPushStoreTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
