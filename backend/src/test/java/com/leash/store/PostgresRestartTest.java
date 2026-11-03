package com.leash.store;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The same restart test on a real Postgres (the version docker-compose.yml runs), so the Flyway migrations and every
 * query are checked against the database production uses, not only H2's imitation of it. Skipped when Docker isn't
 * running.
 */
@EnabledIf("dockerAvailable")
class PostgresRestartTest extends RestartPersistenceTest {

    static PostgreSQLContainer pg;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void start() {
        pg = new PostgreSQLContainer("postgres:17-alpine");
        pg.start();
    }

    @AfterAll
    static void stop() {
        if (pg != null) pg.stop();
    }

    @Override
    String url() {
        return pg.getJdbcUrl();
    }

    @Override
    String user() {
        return pg.getUsername();
    }

    @Override
    String password() {
        return pg.getPassword();
    }
}
