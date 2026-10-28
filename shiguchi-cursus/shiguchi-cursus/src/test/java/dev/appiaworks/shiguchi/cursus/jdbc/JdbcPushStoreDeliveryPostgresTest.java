package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreDelivery on Postgres over pgjdbc")
class JdbcPushStoreDeliveryPostgresTest extends JdbcPushStoreDeliveryTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.POSTGRES;
    }
}
