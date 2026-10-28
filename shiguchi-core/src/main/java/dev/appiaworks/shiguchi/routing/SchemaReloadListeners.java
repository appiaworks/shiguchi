package dev.appiaworks.shiguchi.routing;

import java.util.List;
import org.slf4j.Logger;

/** Fans a reload out to every listener, isolating each from the others' failures. */
public final class SchemaReloadListeners {

    private SchemaReloadListeners() {}

    public static void notifyAll(List<? extends SchemaReloadListener> listeners, Logger log) {
        if (listeners == null) return;
        for (SchemaReloadListener listener : listeners) {
            try {
                listener.onSchemaReloaded();
            } catch (Exception e) {
                log.warn(
                        "Schema reload listener {} failed: {}",
                        listener.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
    }
}
