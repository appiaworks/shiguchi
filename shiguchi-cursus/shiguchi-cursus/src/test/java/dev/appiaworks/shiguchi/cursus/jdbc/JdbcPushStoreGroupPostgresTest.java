package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore consumer groups on Postgres over pgjdbc")
class JdbcPushStoreGroupPostgresTest extends JdbcPushStoreGroupTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
