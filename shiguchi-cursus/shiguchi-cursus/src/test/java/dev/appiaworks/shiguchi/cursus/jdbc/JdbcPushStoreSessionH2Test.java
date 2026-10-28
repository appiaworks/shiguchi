package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreSession on H2 (native mode)")
class JdbcPushStoreSessionH2Test extends JdbcPushStoreSessionTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
