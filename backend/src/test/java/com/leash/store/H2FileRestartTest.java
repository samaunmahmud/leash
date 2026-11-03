package com.leash.store;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Restart persistence on an H2 file database in PostgreSQL mode: runs everywhere, no Docker needed. */
class H2FileRestartTest extends RestartPersistenceTest {

    @TempDir
    static Path dir;

    @Override
    String url() {
        return "jdbc:h2:file:" + dir.resolve("leash") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    }

    @Override
    String user() {
        return "sa";
    }

    @Override
    String password() {
        return "";
    }
}
