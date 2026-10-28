package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreArchive on Postgres over pgjdbc")
class JdbcPushStoreArchivePostgresTest extends JdbcPushStoreArchiveTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
