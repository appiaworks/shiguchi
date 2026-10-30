package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.ReloadSchemaIndicator;
import dev.appiaworks.shiguchi.routing.SchemaReloadListener;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BDD-style unit tests for {@link GatewaySchemaProvider}, including its
 * {@link SchemaReloadListener#onSchemaReloaded()} behaviour.
 *
 * <p>{@link GraphQlSchemaBuilder} is mocked since only its {@code getGeneratedSdl()} /
 * {@code rebuild()} contract matters here; SDL strings are small so parsing is exercised with
 * real graphql-java {@link graphql.parser.Parser} / {@link TypeDefinitionRegistry} classes.
 */
@DisplayName("GatewaySchemaProvider")
class GatewaySchemaProviderTest {

    private static final String SIMPLE_SDL = """
            type Query {
              hello: String
            }
            """;

    @Test
    @DisplayName(
            "Given get() called for the first time, when the SDL is valid, should parse it into a TypeDefinitionRegistry containing the declared type")
    void given_firstCallToGet_when_sdlValid_should_parseIntoRegistry() {
        // Arrange
        GraphQlSchemaBuilder schemaBuilder = mock(GraphQlSchemaBuilder.class);
        when(schemaBuilder.getGeneratedSdl()).thenReturn(SIMPLE_SDL);
        GatewaySchemaProvider provider = new GatewaySchemaProvider(schemaBuilder);

        // Act
        TypeDefinitionRegistry registry = provider.get();

        // Assert
        assertThat(registry.getType("Query")).isPresent();
    }

    @Test
    @DisplayName(
            "Given get() is called twice without a reload, should only parse the SDL once (cached registry reused)")
    void given_getCalledTwiceWithoutReload_should_parseOnlyOnce() {
        // Arrange
        GraphQlSchemaBuilder schemaBuilder = mock(GraphQlSchemaBuilder.class);
        when(schemaBuilder.getGeneratedSdl()).thenReturn(SIMPLE_SDL);
        GatewaySchemaProvider provider = new GatewaySchemaProvider(schemaBuilder);

        // Act
        TypeDefinitionRegistry first = provider.get();
        TypeDefinitionRegistry second = provider.get();

        // Assert — same registry instance; getGeneratedSdl() called only during the first get()
        assertThat(second).isSameAs(first);
        verify(schemaBuilder, times(1)).getGeneratedSdl();
    }

    @Test
    @DisplayName(
            "Given onSchemaReloaded is called, should rebuild the schema builder and re-parse the newly generated SDL")
    void given_onSchemaReloadedCalled_should_rebuildAndReparseSdl() {
        // Arrange
        GraphQlSchemaBuilder schemaBuilder = mock(GraphQlSchemaBuilder.class);
        when(schemaBuilder.getGeneratedSdl()).thenReturn(SIMPLE_SDL).thenReturn("""
                        type Query {
                          hello: String
                          goodbye: String
                        }
                        """);
        GatewaySchemaProvider provider = new GatewaySchemaProvider(schemaBuilder);
        provider.get(); // initial parse

        // Act — SchemaReloadListener contract
        SchemaReloadListener listener = provider;
        listener.onSchemaReloaded();

        // Assert
        verify(schemaBuilder, times(1)).rebuild();
        TypeDefinitionRegistry reloaded = provider.get();
        assertThat(fieldNames(reloaded, "Query")).contains("hello", "goodbye");
    }

    @Test
    @DisplayName(
            "Given reload() is called, should always call rebuild() on the schema builder even when get() was never called before")
    void given_reloadCalledBeforeAnyGet_should_callRebuildOnSchemaBuilder() {
        // Arrange
        GraphQlSchemaBuilder schemaBuilder = mock(GraphQlSchemaBuilder.class);
        when(schemaBuilder.getGeneratedSdl()).thenReturn(SIMPLE_SDL);
        GatewaySchemaProvider provider = new GatewaySchemaProvider(schemaBuilder);

        // Act
        provider.reload();

        // Assert
        verify(schemaBuilder, times(1)).rebuild();
        verify(schemaBuilder, times(1)).getGeneratedSdl();
    }

    @Test
    @DisplayName(
            "Given the generated SDL fails to parse (malformed syntax), when get() called, should return an empty TypeDefinitionRegistry instead of throwing")
    void given_malformedSdl_when_get_should_returnEmptyRegistryWithoutThrowing() {
        // Arrange
        GraphQlSchemaBuilder schemaBuilder = mock(GraphQlSchemaBuilder.class);
        when(schemaBuilder.getGeneratedSdl()).thenReturn("type Query { this is not valid SDL @@@");
        GatewaySchemaProvider provider = new GatewaySchemaProvider(schemaBuilder);

        // Act
        TypeDefinitionRegistry registry = provider.get();

        // Assert
        assertThat(registry.getType("Query")).isEmpty();
        assertThat(registry.types()).isEmpty();
    }

    @Test
    @DisplayName(
            "Given the generated SDL redeclares the same type twice, when get() called, should keep the first definition and not throw")
    void given_sdlWithDuplicateTypeDefinition_when_get_should_ignoreDuplicateAndKeepFirst() {
        // Arrange — two distinct "type Query" blocks parse as two separate SDL definitions;
        // TypeDefinitionRegistry.add() rejects the second with a SchemaProblem, which the
        // provider catches per-definition and logs, keeping the first.
        String conflictingSdl = """
                type Query {
                  hello: String
                }
                type Query {
                  duplicate: String
                }
                """;
        GraphQlSchemaBuilder schemaBuilder = mock(GraphQlSchemaBuilder.class);
        when(schemaBuilder.getGeneratedSdl()).thenReturn(conflictingSdl);
        GatewaySchemaProvider provider = new GatewaySchemaProvider(schemaBuilder);

        // Act
        TypeDefinitionRegistry registry = provider.get();

        // Assert — first "Query" definition retained, conflict swallowed rather than thrown
        assertThat(fieldNames(registry, "Query")).contains("hello").doesNotContain("duplicate");
    }

    @Test
    @DisplayName(
            "GIVEN a provider wired to a reload trigger WHEN reload() is called SHOULD arm the trigger so the wrapped DGS indicator answers true")
    void given_providerWithReloadTrigger_when_reload_should_armTriggerForDgsIndicator() {
        // Arrange
        GraphQlSchemaBuilder schemaBuilder = mock(GraphQlSchemaBuilder.class);
        when(schemaBuilder.getGeneratedSdl()).thenReturn(SIMPLE_SDL);
        GatewaySchemaReloadTrigger trigger = new GatewaySchemaReloadTrigger();
        ReloadSchemaIndicator dgsIndicator = (ReloadSchemaIndicator)
                trigger.postProcessAfterInitialization((ReloadSchemaIndicator) () -> false, "dgsReloadIndicator");
        GatewaySchemaProvider provider = new GatewaySchemaProvider(schemaBuilder, trigger);

        // Act
        provider.reload();

        // Assert
        assertThat(dgsIndicator.reloadSchema()).isTrue();
    }

    private static List<String> fieldNames(TypeDefinitionRegistry registry, String typeName) {
        ObjectTypeDefinition def =
                (ObjectTypeDefinition) registry.getType(typeName).orElseThrow();
        return def.getFieldDefinitions().stream().map(FieldDefinition::getName).toList();
    }
}
