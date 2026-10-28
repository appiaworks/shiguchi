package dev.appiaworks.shiguchi.cursus.postgres;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.r2dbc.core.DatabaseClient;

/** One embedded Postgres per JVM, shared by the Postgres engine tests (each uses its own tables). */
public final class EmbeddedPg {

    private static EmbeddedPostgres postgres;

    private EmbeddedPg() {}

    private static synchronized EmbeddedPostgres instance() {
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
        return postgres;
    }

    public static int port() {
        return instance().getPort();
    }

    static ConnectionFactory connectionFactory() {
        return ConnectionFactories.get("r2dbc:postgresql://postgres:postgres@localhost:" + port() + "/postgres");
    }

    static DatabaseClient client() {
        return DatabaseClient.create(connectionFactory());
    }
}
