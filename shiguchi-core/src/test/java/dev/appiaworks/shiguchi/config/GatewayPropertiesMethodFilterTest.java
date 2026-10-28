package dev.appiaworks.shiguchi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style tests for ServiceDefinition method exposure filtering
 * and deadline resolution.
 *
 * Tests verify that:
 * - isMethodExposed respects include/exclude glob patterns with proper precedence
 * - Exclusion always wins over inclusion
 * - Pattern matching handles wildcards, literal dots, and method-name-only patterns
 * - effectiveDeadline returns the correct value based on per-service and global settings
 */
class GatewayPropertiesMethodFilterTest {

    private ServiceDefinition service;

    // ── isMethodExposed: include/exclude filtering ──

    @Test
    @DisplayName("Given empty include and exclude lists, when isMethodExposed is called, should expose all methods")
    void given_emptyIncludeExclude_when_isMethodExposed_should_exposeAllMethods() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(new ArrayList<>());
        service.setExcludeMethods(new ArrayList<>());

        // Act & Assert
        assertThat(service.isMethodExposed("pkg.UserService", "GetUser")).isTrue();
        assertThat(service.isMethodExposed("pkg.UserService", "ListUsers")).isTrue();
        assertThat(service.isMethodExposed("admin.AdminService", "AdminDebug")).isTrue();
    }

    @Test
    @DisplayName("Given exclude glob '*/Debug*', when isMethodExposed with matching method, should hide the method")
    void given_excludeGlobDebugStar_when_isMethodExposed_should_hideDebugMethods() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(new ArrayList<>());
        service.setExcludeMethods(List.of("*/Debug*"));

        // Act & Assert
        assertThat(service.isMethodExposed("pkg.Service", "DebugStatus")).isFalse();
        assertThat(service.isMethodExposed("pkg.Service", "DebugLog")).isFalse();
        assertThat(service.isMethodExposed("admin.AdminService", "DebugReload")).isFalse();
        assertThat(service.isMethodExposed("pkg.Service", "GetUser")).isTrue(); // Not excluded
    }

    @Test
    @DisplayName(
            "Given include list with specific patterns, when isMethodExposed with non-matching method, should not expose the method")
    void given_includeList_when_isMethodExposed_should_restrictToIncludedMethods() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(List.of("pkg.UserService/Get*", "List*"));
        service.setExcludeMethods(new ArrayList<>());

        // Act & Assert
        assertThat(service.isMethodExposed("pkg.UserService", "GetUser")).isTrue(); // Matches pattern 1
        assertThat(service.isMethodExposed("pkg.UserService", "GetById")).isTrue(); // Matches pattern 1
        assertThat(service.isMethodExposed("pkg.UserService", "ListUsers")).isTrue(); // Matches pattern 2
        assertThat(service.isMethodExposed("pkg.UserService", "DeleteUser")).isFalse(); // Not in include
    }

    @Test
    @DisplayName(
            "Given method in both include and exclude lists, when isMethodExposed, should exclude (exclusion wins)")
    void given_methodInBothIncludeExclude_when_isMethodExposed_should_exclude() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(List.of("pkg.Service/Get*"));
        service.setExcludeMethods(List.of("pkg.Service/GetSecret"));

        // Act & Assert
        assertThat(service.isMethodExposed("pkg.Service", "GetUser")).isTrue(); // In include, not in exclude
        assertThat(service.isMethodExposed("pkg.Service", "GetSecret")).isFalse(); // In both → exclude wins
    }

    @Test
    @DisplayName("Given pattern without '/', when isMethodExposed, should match method name alone")
    void given_patternWithoutSlash_when_isMethodExposed_should_matchMethodNameOnly() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(List.of("Get*"));
        service.setExcludeMethods(new ArrayList<>());

        // Act & Assert
        assertThat(service.isMethodExposed("pkg.UserService", "GetUser")).isTrue(); // Method name matches Get*
        assertThat(service.isMethodExposed("admin.AdminService", "GetSecret")).isTrue(); // Method name matches Get*
        assertThat(service.isMethodExposed("pkg.UserService", "ListUsers")).isFalse(); // Method name doesn't match
    }

    @Test
    @DisplayName(
            "Given literal pattern without wildcards, when isMethodExposed with exact match, should expose the method")
    void given_literalPattern_when_isMethodExposed_should_matchExactly() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(List.of("GetUser"));
        service.setExcludeMethods(new ArrayList<>());

        // Act & Assert
        assertThat(service.isMethodExposed("pkg.UserService", "GetUser")).isTrue(); // Exact match
        assertThat(service.isMethodExposed("pkg.UserService", "GetUserById")).isFalse(); // Not exact
        assertThat(service.isMethodExposed("pkg.UserService", "Get")).isFalse(); // Not exact
    }

    @Test
    @DisplayName(
            "Given pattern with regex metacharacters (dot, question mark), when isMethodExposed, should treat them literally")
    void given_regexMetachars_when_isMethodExposed_should_treatLiterally() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(List.of("Get.x", "List?Items"));
        service.setExcludeMethods(new ArrayList<>());

        // Act & Assert
        assertThat(service.isMethodExposed("pkg.Service", "Get.x")).isTrue(); // Literal dot match
        assertThat(service.isMethodExposed("pkg.Service", "GetAx")).isFalse(); // Dot doesn't wildcard
        assertThat(service.isMethodExposed("pkg.Service", "List?Items")).isTrue(); // Literal question mark
        assertThat(service.isMethodExposed("pkg.Service", "ListAItems")).isFalse(); // Question mark doesn't wildcard
    }

    @Test
    @DisplayName(
            "Given exclude pattern with full service path, when isMethodExposed, should match against full qualified name")
    void given_excludePatternWithServicePath_when_isMethodExposed_should_matchFullQualifiedName() {
        // Arrange
        service = new ServiceDefinition();
        service.setIncludeMethods(new ArrayList<>());
        service.setExcludeMethods(List.of("admin.AdminService/*"));

        // Act & Assert
        assertThat(service.isMethodExposed("admin.AdminService", "DeleteUser")).isFalse(); // Matches exclude
        assertThat(service.isMethodExposed("admin.AdminService", "GetSecret")).isFalse(); // Matches exclude
        assertThat(service.isMethodExposed("pkg.UserService", "DeleteUser"))
                .isTrue(); // Different service, not excluded
    }

    // ── effectiveDeadline: per-service vs global ──

    @Test
    @DisplayName(
            "Given service with per-service deadline set, when effectiveDeadline is called, should return the per-service value")
    void given_perServiceDeadlineSet_when_effectiveDeadline_should_returnPerServiceValue() {
        // Arrange
        service = new ServiceDefinition();
        Duration perServiceDeadline = Duration.ofSeconds(30);
        service.setDeadline(perServiceDeadline);
        Duration globalDefault = Duration.ofSeconds(60);

        // Act & Assert
        assertThat(service.effectiveDeadline(globalDefault)).isEqualTo(perServiceDeadline);
    }

    @Test
    @DisplayName(
            "Given service with null per-service deadline, when effectiveDeadline is called, should return the global default")
    void given_nullPerServiceDeadline_when_effectiveDeadline_should_returnGlobalDefault() {
        // Arrange
        service = new ServiceDefinition();
        service.setDeadline(null);
        Duration globalDefault = Duration.ofSeconds(60);

        // Act & Assert
        assertThat(service.effectiveDeadline(globalDefault)).isEqualTo(globalDefault);
    }
}
