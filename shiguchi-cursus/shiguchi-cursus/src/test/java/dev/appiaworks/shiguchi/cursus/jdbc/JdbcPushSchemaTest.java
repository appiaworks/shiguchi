package dev.appiaworks.shiguchi.cursus.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("JdbcPushSchema and JdbcDialect")
class JdbcPushSchemaTest {

    @Test
    @DisplayName("GIVEN plain and schema-qualified names WHEN validating the table SHOULD return them unchanged")
    void given_plainAndQualifiedNames_when_validatingTable_should_returnUnchanged() {
        assertThat(JdbcPushSchema.table("push_event")).isEqualTo("push_event");
        assertThat(JdbcPushSchema.table("push.events")).isEqualTo("push.events");
    }

    @Test
    @DisplayName("GIVEN unsafe or malformed names WHEN validating the table SHOULD reject them naming the property")
    void given_unsafeNames_when_validatingTable_should_reject() {
        for (String bad : new String[] {"push-event", "1abc", null, "x; drop table y"}) {
            assertThatThrownBy(() -> JdbcPushSchema.table(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cursus.jdbc.table");
        }
    }

    @ParameterizedTest
    @EnumSource(JdbcDialect.class)
    @DisplayName("GIVEN any dialect WHEN building the statements SHOULD give thirteen, fourteen with the archive index")
    void given_anyDialect_when_buildingStatements_should_countThirteenOrFourteen(JdbcDialect dialect) {
        List<String> plain = JdbcPushSchema.statements(dialect, "push_event");
        List<String> archive = JdbcPushSchema.statements(dialect, "push_event", true);

        assertThat(plain).hasSize(13);
        assertThat(archive).hasSize(14);
        assertThat(archive.get(13)).contains("push_event_time ON push_event (event_time)");
    }

    @ParameterizedTest
    @EnumSource(JdbcDialect.class)
    @DisplayName("GIVEN any dialect WHEN building the statements SHOULD create every table the store reads and writes")
    void given_anyDialect_when_buildingStatements_should_createEveryTable(JdbcDialect dialect) {
        List<String> statements = JdbcPushSchema.statements(dialect, "push_event");

        for (String suffix : List.of("", "_seq", "_lease", "_delivery", "_session", "_group", "_connection", "_trim")) {
            assertThat(statements)
                    .anyMatch(s -> s.matches("CREATE TABLE (IF NOT EXISTS )?push_event" + suffix + " \\(.*"));
        }
        assertThat(statements.get(0)).contains("id ").contains("PRIMARY KEY").contains("in_history");
    }

    @ParameterizedTest
    @EnumSource(
            value = JdbcDialect.class,
            names = {"H2", "POSTGRES"})
    @DisplayName("GIVEN a dialect with IF NOT EXISTS for tables and indexes WHEN building SHOULD use it everywhere")
    void given_ifNotExistsDialect_when_building_should_useItEverywhere(JdbcDialect dialect) {
        assertThat(JdbcPushSchema.statements(dialect, "push_event", true))
                .filteredOn(s -> s.startsWith("CREATE"))
                .allMatch(s -> s.contains("IF NOT EXISTS"));
    }

    @ParameterizedTest
    @EnumSource(
            value = JdbcDialect.class,
            names = {"SQLSERVER", "ORACLE", "GENERIC"})
    @DisplayName("GIVEN a dialect without IF NOT EXISTS WHEN building SHOULD emit plain CREATEs the runner tolerates")
    void given_noIfNotExistsDialect_when_building_should_emitPlainCreates(JdbcDialect dialect) {
        assertThat(JdbcPushSchema.statements(dialect, "push_event", true))
                .filteredOn(s -> s.startsWith("CREATE"))
                .noneMatch(s -> s.contains("IF NOT EXISTS"));
    }

    @Test
    @DisplayName(
            "GIVEN the dialects WHEN naming the counter seed SHOULD select from dual only where a FROM is required")
    void given_dialects_when_namingSeed_should_useDualOnlyWhereRequired() {
        assertThat(JdbcPushSchema.statements(JdbcDialect.ORACLE, "t").get(4)).contains("FROM dual WHERE NOT EXISTS");
        assertThat(JdbcPushSchema.statements(JdbcDialect.H2, "t").get(4)).doesNotContain("dual");
        assertThat(JdbcPushSchema.statements(JdbcDialect.SQLSERVER, "t").get(4)).doesNotContain("dual");
    }

    @Test
    @DisplayName("GIVEN database product names WHEN resolving the dialect SHOULD map them, unknown to GENERIC")
    void given_productNames_when_resolvingDialect_should_mapThem() {
        assertThat(JdbcDialect.fromProductName("H2")).isEqualTo(JdbcDialect.H2);
        assertThat(JdbcDialect.fromProductName("PostgreSQL")).isEqualTo(JdbcDialect.POSTGRES);
        assertThat(JdbcDialect.fromProductName("MySQL")).isEqualTo(JdbcDialect.MYSQL);
        assertThat(JdbcDialect.fromProductName("MariaDB")).isEqualTo(JdbcDialect.MYSQL);
        assertThat(JdbcDialect.fromProductName("Microsoft SQL Server")).isEqualTo(JdbcDialect.SQLSERVER);
        assertThat(JdbcDialect.fromProductName("Oracle")).isEqualTo(JdbcDialect.ORACLE);
        assertThat(JdbcDialect.fromProductName("Derby")).isEqualTo(JdbcDialect.GENERIC);
        assertThat(JdbcDialect.fromProductName(null)).isEqualTo(JdbcDialect.GENERIC);
    }

    @Test
    @DisplayName("GIVEN the dialects WHEN paging SHOULD use LIMIT on MySQL and OFFSET/FETCH elsewhere")
    void given_dialects_when_paging_should_pickTheClause() {
        assertThat(JdbcDialect.MYSQL.page(0, 5)).isEqualTo(" LIMIT 5");
        assertThat(JdbcDialect.MYSQL.page(10, 5)).isEqualTo(" LIMIT 5 OFFSET 10");
        assertThat(JdbcDialect.H2.page(10, 5)).isEqualTo(" OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY");
        assertThat(JdbcDialect.SQLSERVER.page(0, 1)).isEqualTo(" OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY");
    }

    @Test
    @DisplayName("GIVEN vendor errors WHEN asked if the object exists SHOULD recognise the duplicate-object ones")
    void given_vendorErrors_when_askedIfExists_should_recogniseDuplicateObject() {
        RuntimeException wrapped = new RuntimeException("x", new SQLException("exists", "42P07", 0));

        assertThat(JdbcDialect.POSTGRES.isAlreadyExists(wrapped)).isTrue();
        assertThat(JdbcDialect.ORACLE.isAlreadyExists(new SQLException("ORA-00955", "42000", 955)))
                .isTrue();
        assertThat(JdbcDialect.SQLSERVER.isAlreadyExists(new SQLException("exists", "S0001", 2714)))
                .isTrue();
        assertThat(JdbcDialect.MYSQL.isAlreadyExists(new SQLException("dup key name", "42000", 1061)))
                .isTrue();
        assertThat(JdbcDialect.H2.isAlreadyExists(new SQLException("syntax", "42000", 42000)))
                .isFalse();
    }
}
