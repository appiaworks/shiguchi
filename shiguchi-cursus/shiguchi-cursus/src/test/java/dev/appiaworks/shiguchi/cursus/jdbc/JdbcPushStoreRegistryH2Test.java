package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreRegistry on H2 (native mode)")
class JdbcPushStoreRegistryH2Test extends JdbcPushStoreRegistryTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
