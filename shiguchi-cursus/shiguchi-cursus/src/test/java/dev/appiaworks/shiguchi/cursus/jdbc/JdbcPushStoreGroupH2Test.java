package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore consumer groups on H2 (native mode)")
class JdbcPushStoreGroupH2Test extends JdbcPushStoreGroupTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
