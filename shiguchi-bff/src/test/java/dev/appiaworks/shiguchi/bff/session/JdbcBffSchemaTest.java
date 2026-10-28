package dev.appiaworks.shiguchi.bff.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSchema;
import dev.appiaworks.shiguchi.bff.session.jdbc.JdbcBffSchema.Dialect;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("JdbcBffSchema")
class JdbcBffSchemaTest {

    @ParameterizedTest
    @EnumSource(Dialect.class)
    @DisplayName(
            "GIVEN any dialect WHEN building the statements SHOULD give ten: three tables, their expiry indexes, two lease columns, two lookup indexes")
    void given_any_dialect_when_building_should_give_fourteen_statements(Dialect dialect) {
        List<String> statements = JdbcBffSchema.statements(dialect, "gw_bff_");

        assertThat(statements).hasSize(16);
        assertThat(statements.get(4)).startsWith("ALTER TABLE gw_bff_session ").contains("lease_owner");
        assertThat(statements.get(5)).startsWith("ALTER TABLE gw_bff_session ").contains("lease_until");
        assertThat(statements.subList(10, 13))
                .allSatisfy(sql -> assertThat(sql).startsWith("ALTER TABLE gw_bff_session "));
        assertThat(statements.get(10)).contains("acr");
        assertThat(statements.get(11)).contains("amr");
        assertThat(statements.get(12)).contains("auth_time");
        assertThat(statements.get(13)).startsWith("ALTER TABLE gw_bff_login ").contains("max_age");
        assertThat(statements.get(14)).startsWith("ALTER TABLE gw_bff_session ").contains("claims");
        assertThat(statements.get(15)).startsWith("ALTER TABLE gw_bff_session ").contains("user_agent");
        assertThat(statements.get(0))
                .contains("gw_bff_session (")
                .contains("session_key")
                .contains("PRIMARY KEY");
        assertThat(statements.get(1)).contains("gw_bff_session (expires_at)");
        assertThat(statements.get(2))
                .contains("gw_bff_login (")
                .contains("state")
                .contains("PRIMARY KEY");
        assertThat(statements.get(3)).contains("gw_bff_login (expires_at)");
        assertThat(statements.get(6))
                .contains("gw_bff_jti (")
                .contains("jti_key")
                .contains("PRIMARY KEY");
        assertThat(statements.get(7)).contains("gw_bff_jti (expires_at)");
        assertThat(statements.get(8)).contains("gw_bff_session (provider, subject)");
        assertThat(statements.get(9)).contains("gw_bff_session (provider, sid)");
    }

    @ParameterizedTest
    @EnumSource(
            value = Dialect.class,
            names = {"H2", "POSTGRES"})
    @DisplayName("GIVEN H2 or Postgres WHEN building SHOULD use IF NOT EXISTS everywhere")
    void given_if_not_exists_dialect_when_building_should_use_it_everywhere(Dialect dialect) {
        assertThat(JdbcBffSchema.statements(dialect, "gw_bff_")).allMatch(s -> s.contains("IF NOT EXISTS"));
    }

    @Test
    @DisplayName("GIVEN a prefix that is not an identifier WHEN naming the tables SHOULD reject it naming the property")
    void given_unsafe_prefix_when_naming_tables_should_reject() {
        assertThatThrownBy(() -> JdbcBffSchema.sessionTable("x; drop table y;"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bff.session.jdbc.table-prefix");
        assertThat(JdbcBffSchema.loginTable("app.gw_")).isEqualTo("app.gw_login");
    }

    @Test
    @DisplayName("GIVEN database product names WHEN resolving the dialect SHOULD map them, unknown to GENERIC")
    void given_product_names_when_resolving_should_map_them() {
        assertThat(Dialect.fromProductName("H2")).isEqualTo(Dialect.H2);
        assertThat(Dialect.fromProductName("PostgreSQL")).isEqualTo(Dialect.POSTGRES);
        assertThat(Dialect.fromProductName("MariaDB")).isEqualTo(Dialect.MYSQL);
        assertThat(Dialect.fromProductName(null)).isEqualTo(Dialect.GENERIC);
    }
}
