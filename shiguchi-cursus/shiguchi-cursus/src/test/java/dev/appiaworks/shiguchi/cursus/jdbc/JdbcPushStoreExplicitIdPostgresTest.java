package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreExplicitId on Postgres over pgjdbc")
class JdbcPushStoreExplicitIdPostgresTest extends JdbcPushStoreExplicitIdTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
