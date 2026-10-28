package dev.appiaworks.shiguchi.sample.staticschema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A gateway in front of a backend that does <em>not</em> serve gRPC reflection.
 *
 * <p>Most deployments use {@code mode: DYNAMIC}, where the gateway asks the backend what it
 * serves. When that is not available — reflection disabled by policy, an older gRPC library, or a
 * backend you do not control — the schema has to come from somewhere else. There are two
 * somewhere-elses, and this sample runs both against the same backend:
 *
 * <ul>
 *   <li><b>default profile — {@code STATIC}</b>: the generated stubs are on the gateway's own
 *       classpath, and the loader reads the descriptors straight out of them.
 *   <li><b>{@code pb} profile — {@code PB_FILE}</b>: the gateway reads a {@code FileDescriptorSet}
 *       produced by {@code protoc}, so it needs the descriptor file but no compiled classes.
 * </ul>
 *
 * <p>Same routes either way, which is the point: {@code /actuator/gateway-routes} reports the same
 * two routes under both, and only the {@code mode} differs.
 */
@SpringBootApplication
public class SampleStaticSchemaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleStaticSchemaApplication.class, args);
    }
}
