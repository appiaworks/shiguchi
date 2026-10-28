package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore origin attribute and session skip on H2 (native mode)")
class JdbcPushStoreOriginH2Test extends JdbcPushStoreOriginTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
