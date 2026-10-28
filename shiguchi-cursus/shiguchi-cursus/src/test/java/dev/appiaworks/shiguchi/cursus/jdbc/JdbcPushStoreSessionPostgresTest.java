package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreSession on Postgres over pgjdbc")
class JdbcPushStoreSessionPostgresTest extends JdbcPushStoreSessionTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
