package com.leash.mandate;

import com.leash.policy.Mandate;
import com.leash.policy.Period;
import com.leash.store.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MandateRepository {

    /** A mandate plus the English text it came from, which the domain record doesn't need. */
    public record Stored(Mandate mandate, String sourceText, Instant updatedAt) {}

    private final JdbcClient jdbc;

    public MandateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Stored> findAll() {
        return jdbc.sql("SELECT * FROM mandates ORDER BY id").query(MandateRepository::map).list();
    }

    public Optional<Stored> findByAgent(String agentId) {
        return jdbc.sql("SELECT * FROM mandates WHERE agent_id = ?").param(agentId).query(MandateRepository::map).optional();
    }

    public void upsert(Mandate m, String sourceText, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE mandates SET name = ?, currency = ?, period = ?, period_limit_minor = ?,
                          auto_approve_up_to_minor = ?, per_purchase_max_minor = ?, allowed_merchants = ?,
                          blocked_merchants = ?, allowed_categories = ?, blocked_categories = ?,
                          max_purchases_per_hour = ?, quiet_start = ?, quiet_end = ?, active = ?, source_text = ?,
                          updated_at = ?
                        WHERE id = ?""")
                .params(m.name(), m.currency(), m.period().name(), m.periodLimitMinor(), m.autoApproveUpToMinor(),
                        m.perPurchaseMaxMinor(), Db.lines(m.allowedMerchants()), Db.lines(m.blockedMerchants()),
                        Db.lines(m.allowedCategories()), Db.lines(m.blockedCategories()), m.maxPurchasesPerHour(),
                        time(m.quietStart()), time(m.quietEnd()), m.active(), sourceText, Db.ts(now), m.id())
                .update();
        if (updated == 1) return;
        jdbc.sql("""
                        INSERT INTO mandates (id, agent_id, name, currency, period, period_limit_minor,
                          auto_approve_up_to_minor, per_purchase_max_minor, allowed_merchants, blocked_merchants,
                          allowed_categories, blocked_categories, max_purchases_per_hour, quiet_start, quiet_end,
                          active, source_text, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(m.id(), m.agentId(), m.name(), m.currency(), m.period().name(), m.periodLimitMinor(),
                        m.autoApproveUpToMinor(), m.perPurchaseMaxMinor(), Db.lines(m.allowedMerchants()),
                        Db.lines(m.blockedMerchants()), Db.lines(m.allowedCategories()), Db.lines(m.blockedCategories()),
                        m.maxPurchasesPerHour(), time(m.quietStart()), time(m.quietEnd()), m.active(), sourceText,
                        Db.ts(now))
                .update();
    }

    private static String time(LocalTime t) {
        return t == null ? null : t.toString();
    }

    private static LocalTime time(String s) {
        return s == null || s.isBlank() ? null : LocalTime.parse(s);
    }

    static Stored map(ResultSet rs, int row) throws SQLException {
        Mandate m = new Mandate(rs.getString("id"), rs.getString("agent_id"), rs.getString("name"),
                rs.getString("currency"), Period.valueOf(rs.getString("period")), rs.getLong("period_limit_minor"),
                rs.getLong("auto_approve_up_to_minor"), rs.getLong("per_purchase_max_minor"),
                Db.set(rs.getString("allowed_merchants")), Db.set(rs.getString("blocked_merchants")),
                Db.set(rs.getString("allowed_categories")), Db.set(rs.getString("blocked_categories")),
                rs.getInt("max_purchases_per_hour"), time(rs.getString("quiet_start")), time(rs.getString("quiet_end")),
                rs.getBoolean("active"));
        return new Stored(m, rs.getString("source_text"), Db.instant(rs, "updated_at"));
    }
}
