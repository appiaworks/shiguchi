package dev.appiaworks.shiguchi.discovery;

import dev.appiaworks.shiguchi.config.ServiceDefinition;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * The service definitions the registry synchronizer built and loaded. Kept apart from
 * {@code shiguchi.services} so they never reach configuration validation or the property view; the
 * refresh scheduler reads both lists.
 */
@Component
public class AutoRegisteredServices {

    private final List<ServiceDefinition> definitions = new CopyOnWriteArrayList<>();

    public List<ServiceDefinition> definitions() {
        return definitions;
    }

    void add(ServiceDefinition definition) {
        definitions.add(definition);
    }

    void remove(String name) {
        definitions.removeIf(d -> name.equals(d.getName()));
    }
}
