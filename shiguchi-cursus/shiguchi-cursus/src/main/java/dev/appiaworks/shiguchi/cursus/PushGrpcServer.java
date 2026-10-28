package dev.appiaworks.shiguchi.cursus;

import io.grpc.Server;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The in-process gRPC server carrying the hosted {@code PushService}. The gateway dials it as the
 * STATIC service {@link PushServiceRegistration} declares, over {@code inprocess:<name>}, so every
 * surface reaches it the way it reaches any backend. Up before the HTTP and gRPC listeners (phase
 * 0, theirs are the last), down after them.
 */
public final class PushGrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PushGrpcServer.class);

    private final String name;
    private final PushGrpcService service;
    private volatile Server server;

    public PushGrpcServer(PushServiceRegistration registration, PushGrpcService service) {
        this.name = registration.inProcessName();
        this.service = service;
    }

    @Override
    public void start() {
        try {
            server = InProcessServerBuilder.forName(name)
                    .addService(service)
                    .build()
                    .start();
            log.info("Push: PushService hosted in-process as inprocess:{}", name);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start the in-process push server " + name, e);
        }
    }

    @Override
    public void stop() {
        Server s = server;
        server = null;
        if (s == null) return;
        s.shutdown();
        try {
            if (!s.awaitTermination(5, TimeUnit.SECONDS)) s.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }
}
