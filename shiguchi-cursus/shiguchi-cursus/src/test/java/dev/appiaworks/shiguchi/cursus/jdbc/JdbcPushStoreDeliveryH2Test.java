package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreDelivery on H2 (native mode)")
class JdbcPushStoreDeliveryH2Test extends JdbcPushStoreDeliveryTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
