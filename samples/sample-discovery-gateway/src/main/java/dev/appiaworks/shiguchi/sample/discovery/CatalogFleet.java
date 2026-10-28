package dev.appiaworks.shiguchi.sample.discovery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Several instances of the catalog backend, standing in for the pods of one deployed service.
 *
 * <p>Component-scanned, so the fleet is listening before the autoconfigured gateway beans are
 * created and the route loader can reflect on it. In a real deployment these are separate
 * processes that are simply up, and the gateway would not know how many there are — which is the
 * point of asking discovery.
 *
 * <p>Each instance binds port 0 and reports the port the OS actually gave it, so the sample never
 * collides with something already listening and the addresses handed to discovery are real rather
 * than assumed.
 */
@Component
public class CatalogFleet {

    private static final Logger log = LoggerFactory.getLogger(CatalogFleet.class);

    private final int size;
    private final List<CatalogServiceServer> instances = new ArrayList<>();

    public CatalogFleet(@Value("${sample.instances:3}") int size) {
        this.size = size;
    }

    @PostConstruct
    public void start() throws Exception {
        for (int i = 1; i <= size; i++) {
            CatalogServiceServer instance = new CatalogServiceServer("catalog-" + i, 0);
            instance.start();
            instances.add(instance);
        }
        log.info(
                "catalog fleet up: {}",
                instances.stream()
                        .map(i -> i.instanceId() + "@localhost:" + i.port())
                        .toList());
    }

    @PreDestroy
    public void stop() {
        instances.forEach(CatalogServiceServer::stop);
    }

    /** The running instances, in the order they were started. */
    public List<CatalogServiceServer> instances() {
        return List.copyOf(instances);
    }

    /** Stops one instance so the gateway has to route around it; returns its id. */
    public String stopOne() {
        CatalogServiceServer victim = instances.remove(instances.size() - 1);
        victim.stop();
        log.info("stopped instance '{}' — discovery will drop it at the next refresh", victim.instanceId());
        return victim.instanceId();
    }
}
