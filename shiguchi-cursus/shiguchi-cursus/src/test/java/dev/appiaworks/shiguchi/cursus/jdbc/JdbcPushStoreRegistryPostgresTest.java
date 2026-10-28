package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreRegistry on Postgres over pgjdbc")
class JdbcPushStoreRegistryPostgresTest extends JdbcPushStoreRegistryTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
