package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreExplicitId on H2 (native mode)")
class JdbcPushStoreExplicitIdH2Test extends JdbcPushStoreExplicitIdTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
