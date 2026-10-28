package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore KEY ordering and seek on H2 (native mode)")
class JdbcPushStoreOrderingH2Test extends JdbcPushStoreOrderingTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
