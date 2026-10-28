package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore schema on H2 (native mode)")
class JdbcPushStoreSchemaH2Test extends JdbcPushStoreSchemaTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
