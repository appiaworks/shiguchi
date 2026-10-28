package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStore trimmed-through watermark on H2 (native mode)")
class JdbcPushStoreGapH2Test extends JdbcPushStoreGapTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
