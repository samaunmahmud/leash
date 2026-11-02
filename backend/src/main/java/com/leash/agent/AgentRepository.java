package com.leash.agent;

import com.leash.store.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentRepository {

    private final JdbcClient jdbc;

    public AgentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Agent> findAll() {
        return jdbc.sql("SELECT * FROM agents ORDER BY created_at, id").query(AgentRepository::map).list();
    }

    public Optional<Agent> find(String id) {
        return jdbc.sql("SELECT * FROM agents WHERE id = ?").param(id).query(AgentRepository::map).optional();
    }

    public void insert(Agent a) {
        jdbc.sql("INSERT INTO agents (id, name, kind, revoked, revoked_at, created_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(a.id(), a.name(), a.kind(), a.revoked(), Db.ts(a.revokedAt()), Db.ts(a.createdAt()))
                .update();
    }

    public void update(Agent a) {
        jdbc.sql("UPDATE agents SET name = ?, kind = ?, revoked = ?, revoked_at = ? WHERE id = ?")
                .params(a.name(), a.kind(), a.revoked(), Db.ts(a.revokedAt()), a.id())
                .update();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM agents").query(Long.class).single();
    }

    static Agent map(ResultSet rs, int row) throws SQLException {
        return new Agent(rs.getString("id"), rs.getString("name"), rs.getString("kind"), rs.getBoolean("revoked"),
                Db.instant(rs, "revoked_at"), Db.instant(rs, "created_at"));
    }
}
