package com.leash.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUrlTest {

    @Test
    void turnsRendersUrlIntoJdbc() {
        var j = DatabaseUrl.parse("postgres://leash_user:p%40ss@dpg-abc123-a:5432/leash_db").orElseThrow();
        assertThat(j.url()).isEqualTo("jdbc:postgresql://dpg-abc123-a:5432/leash_db");
        assertThat(j.user()).isEqualTo("leash_user");
        assertThat(j.password()).isEqualTo("p@ss");
    }

    @Test
    void keepsQueryAndDefaultPort() {
        var j = DatabaseUrl.parse("postgresql://u:pw@db.example.com/leash?sslmode=require").orElseThrow();
        assertThat(j.url()).isEqualTo("jdbc:postgresql://db.example.com/leash?sslmode=require");
        assertThat(j.password()).isEqualTo("pw");
    }

    @org.junit.jupiter.api.AfterEach
    void clear() {
        System.clearProperty("LEASH_DB_URL");
        System.clearProperty("LEASH_DB_USER");
        System.clearProperty("LEASH_DB_PASSWORD");
    }

    @Test
    void usesTheDatabaseWhenItAnswers() {
        DatabaseUrl.applyFrom(java.util.Map.of("DATABASE_URL", "postgres://u:p@db/leash", "LEASH_DB_FALLBACK", "memory"), j -> true);
        assertThat(System.getProperty("LEASH_DB_URL")).isEqualTo("jdbc:postgresql://db/leash");
    }

    @Test
    void fallsBackToMemoryWhenTheDatabaseIsGone() {
        DatabaseUrl.applyFrom(java.util.Map.of("DATABASE_URL", "postgres://u:p@db/leash", "LEASH_DB_FALLBACK", "memory"), j -> false);
        assertThat(System.getProperty("LEASH_DB_URL")).isNull();
    }

    @Test
    void withoutTheFallbackADeadDatabaseStillFailsLoudly() {
        DatabaseUrl.applyFrom(java.util.Map.of("DATABASE_URL", "postgres://u:p@db/leash"), j -> false);
        assertThat(System.getProperty("LEASH_DB_URL")).isEqualTo("jdbc:postgresql://db/leash");
    }

    @Test
    void aClosedPortIsNotReachable() {
        assertThat(DatabaseUrl.reachable(new DatabaseUrl.Jdbc("jdbc:postgresql://127.0.0.1:1/leash", "u", "p"))).isFalse();
    }

    @Test
    void ignoresAnythingElse() {
        assertThat(DatabaseUrl.parse(null)).isEmpty();
        assertThat(DatabaseUrl.parse("")).isEmpty();
        assertThat(DatabaseUrl.parse("mysql://u:p@h/db")).isEmpty();
        assertThat(DatabaseUrl.parse("postgres://u:p@h")).isEmpty();
        assertThat(DatabaseUrl.parse("jdbc:postgresql://h/db")).isEmpty();
    }
}
