package dev.appiaworks.shiguchi.cursus.jdbc;

import org.junit.jupiter.api.DisplayName;

@DisplayName("JdbcPushStoreArchive on H2 (native mode)")
class JdbcPushStoreArchiveH2Test extends JdbcPushStoreArchiveTest {

    @Override
    JdbcTestDatabase database() {
        return JdbcTestDatabase.H2;
    }
}
