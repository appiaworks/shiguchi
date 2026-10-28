package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore schema on Postgres over pgjdbc")
class JdbcPushStoreSchemaPostgresTest extends JdbcPushStoreSchemaTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
