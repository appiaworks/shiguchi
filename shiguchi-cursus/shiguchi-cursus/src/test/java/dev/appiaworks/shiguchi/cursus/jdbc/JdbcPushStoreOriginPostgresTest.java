package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore origin attribute and session skip on Postgres over pgjdbc")
class JdbcPushStoreOriginPostgresTest extends JdbcPushStoreOriginTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
