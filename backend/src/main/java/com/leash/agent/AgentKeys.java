package com.leash.agent;

import com.leash.api.NotFoundException;
import com.leash.store.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Agent keys: how an agent proves which leash it's on. Each agent has at most one key; issuing a new one replaces the
 * old (rotation). Keys are 32 random bytes; only their SHA-256 is stored, so the key is shown exactly once.
 *
 * <p>Why a hash and not bcrypt: the key is 256 bits of randomness, not a human password, so brute force is hopeless
 * and a fast hash lets every request be checked with one indexed lookup.
 */
@Service
public class AgentKeys {

    public static final String PREFIX = "lk_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final AgentRepository agents;
    private final Clock clock;

    public AgentKeys(JdbcClient jdbc, AgentRepository agents, Clock clock) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.clock = clock;
    }

    /** @param key the full key: only ever returned here, never stored or logged */
    public record Issued(String agentId, String key, String prefix) {}

    @Transactional
    public Issued issue(String agentId) {
        if (agents.find(agentId).isEmpty()) throw new NotFoundException("No agent " + agentId);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String prefix = key.substring(0, 10);
        jdbc.sql("DELETE FROM agent_keys WHERE agent_id = ?").param(agentId).update();
        jdbc.sql("INSERT INTO agent_keys (agent_id, key_hash, key_prefix, created_at) VALUES (?, ?, ?, ?)")
                .params(agentId, hash(key), prefix, Db.ts(clock.instant()))
                .update();
        return new Issued(agentId, key, prefix);
    }

    /** The agent a key belongs to, if it's a live key. */
    public Optional<String> agentFor(String key) {
        if (key == null || !key.startsWith(PREFIX) || key.length() > 100) return Optional.empty();
        return jdbc.sql("SELECT agent_id FROM agent_keys WHERE key_hash = ?").param(hash(key))
                .query(String.class).optional();
    }

    /** The visible start of the agent's live key ("lk_Ab3dE9f"), or empty if it has none. */
    public Optional<String> prefixFor(String agentId) {
        return jdbc.sql("SELECT key_prefix FROM agent_keys WHERE agent_id = ?").param(agentId)
                .query(String.class).optional();
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
