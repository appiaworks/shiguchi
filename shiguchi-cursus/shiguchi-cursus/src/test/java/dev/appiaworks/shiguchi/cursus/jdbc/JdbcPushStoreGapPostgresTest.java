package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore trimmed-through watermark on Postgres over pgjdbc")
class JdbcPushStoreGapPostgresTest extends JdbcPushStoreGapTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
