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

    @Test
    void ignoresAnythingElse() {
        assertThat(DatabaseUrl.parse(null)).isEmpty();
        assertThat(DatabaseUrl.parse("")).isEmpty();
        assertThat(DatabaseUrl.parse("mysql://u:p@h/db")).isEmpty();
        assertThat(DatabaseUrl.parse("postgres://u:p@h")).isEmpty();
        assertThat(DatabaseUrl.parse("jdbc:postgresql://h/db")).isEmpty();
    }
}
