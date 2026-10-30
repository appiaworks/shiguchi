package dev.appiaworks.shiguchi.graphql;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsTypeDefinitionRegistry;
import graphql.language.Document;
import graphql.language.SDLDefinition;
import graphql.parser.Parser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DGS type-definition supplier that feeds the dynamically-generated proto SDL into the schema.
 *
 * DGS discovers all beans implementing TypeDefinitionRegistry-compatible suppliers and merges
 * them into the schema. This bean hands DGS the SDL produced by GraphQlSchemaBuilder.
 *
 * reload() runs on every schema reload (a refresh swapped a schema, a deferred service loaded
 * late) and asks GatewaySchemaReloadTrigger for a DGS rebuild, so the next request is served by
 * the new schema without a restart.
 */
@DgsComponent
@Component
@ConditionalOnProperty(prefix = "shiguchi.graphql", name = "enabled", matchIfMissing = true)
public class GatewaySchemaProvider implements dev.appiaworks.shiguchi.routing.SchemaReloadListener {

    private static final Logger log = LoggerFactory.getLogger(GatewaySchemaProvider.class);

    private final GraphQlSchemaBuilder schemaBuilder;
    private final GatewaySchemaReloadTrigger reloadTrigger;
    private volatile TypeDefinitionRegistry registry;

    public GatewaySchemaProvider(GraphQlSchemaBuilder schemaBuilder) {
        this(schemaBuilder, new GatewaySchemaReloadTrigger());
    }

    @Autowired
    public GatewaySchemaProvider(GraphQlSchemaBuilder schemaBuilder, GatewaySchemaReloadTrigger reloadTrigger) {
        this.schemaBuilder = schemaBuilder;
        this.reloadTrigger = reloadTrigger;
    }

    /**
     * Returns the current TypeDefinitionRegistry parsed from the generated SDL.
     * Called by DGS at startup and after any reload().
     */
    @DgsTypeDefinitionRegistry
    public TypeDefinitionRegistry get() {
        if (registry == null) {
            registry = parse(schemaBuilder.getGeneratedSdl());
        }
        return registry;
    }

    /**
     * Regenerates the SDL from the registered services and has DGS rebuild the schema on the next request.
     * Called after registry.replaceContext() by the refresh scheduler and after a deferred service loaded.
     */
    public void reload() {
        schemaBuilder.rebuild();
        registry = parse(schemaBuilder.getGeneratedSdl());
        reloadTrigger.requestReload();
        log.info("GatewaySchemaProvider: schema reloaded");
    }

    @Override
    public void onSchemaReloaded() {
        reload();
    }

    private TypeDefinitionRegistry parse(String sdl) {
        try {
            Document document = new Parser().parseDocument(sdl);
            TypeDefinitionRegistry reg = new TypeDefinitionRegistry();
            document.getDefinitions().forEach(def -> {
                if (!(def instanceof SDLDefinition<?> sdlDef)) return;
                try {
                    reg.add(sdlDef);
                } catch (Exception e) {
                    log.warn("SDL type conflict (ignored): {}", e.getMessage());
                }
            });
            return reg;
        } catch (Exception e) {
            log.error("Failed to parse generated SDL: {}", e.getMessage());
            log.debug("SDL that failed parsing:\n{}", sdl);
            return new TypeDefinitionRegistry();
        }
    }
}
