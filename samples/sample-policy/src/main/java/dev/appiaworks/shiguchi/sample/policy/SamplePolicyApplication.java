package dev.appiaworks.shiguchi.sample.policy;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Policy as code on top of route tags: a {@code GatewayPolicy} whose rules live in a file and are
 * hot-reloaded.
 *
 * <p>The gateway only carries <em>facts</em> about each method (its tags, its idempotency) and
 * calls every {@code GatewayPolicy} bean after the normal authorizer has allowed the request.
 * What the facts mean is decided here, in {@link FileBackedPolicy}. See {@code README.md}.
 */
@SpringBootApplication
@EnableScheduling
public class SamplePolicyApplication {

    public static void main(String[] args) {
        SpringApplication.run(SamplePolicyApplication.class, args);
    }

    /** Fails startup when the first load fails: no rules file means no policy, which is a deny. */
    @Bean
    FileBackedPolicy filePolicy(@Value("${sample.policy.rules-file}") Path rulesFile) {
        return new FileBackedPolicy(rulesFile);
    }

    /** Polls the file; a change is picked up within {@code sample.policy.reload-interval}. */
    @Bean
    PolicyReloader policyReloader(FileBackedPolicy policy) {
        return new PolicyReloader(policy);
    }

    /** The stand-in legacy REST service the gateway fronts, in this process. */
    @Bean(initMethod = "start", destroyMethod = "stop")
    InventoryBackend inventoryBackend(@Value("${sample.backend-port}") int port) {
        return new InventoryBackend(port);
    }
}
