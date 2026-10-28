package dev.appiaworks.shiguchi.bff.session;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;

/** One embedded Postgres per JVM, shared by the Postgres store tests (each test uses its own tables). */
final class EmbeddedPg {

    private static EmbeddedPostgres postgres;

    private EmbeddedPg() {}

    static synchronized int port() {
        if (postgres == null) {
            try {
                postgres = EmbeddedPostgres.start();
            } catch (IOException e) {
                throw new IllegalStateException("cannot start embedded Postgres", e);
            }
            EmbeddedPostgres started = postgres;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.close();
                } catch (IOException ignored) {
                    // shutting down
                }
            }));
        }
        return postgres.getPort();
    }
}
