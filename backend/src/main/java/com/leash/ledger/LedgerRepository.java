package com.leash.ledger;

import com.leash.policy.Decision;
import com.leash.policy.Outcome;
import com.leash.policy.PurchaseRequest;
import com.leash.policy.Reason;
import com.leash.store.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class LedgerRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<Reason>> REASONS = new TypeReference<>() {};
    private static final String SELECT = "SELECT l.*, t.id AS tug_id FROM ledger_entries l LEFT JOIN tugs t ON t.entry_id = l.id ";
    private static final String SPEND_STATUSES = EntryStatus.COUNTS_TOWARD_SPEND.stream()
            .map(s -> "'" + s.name() + "'").collect(Collectors.joining(","));

    /** A tug as stored. */
    public record Tug(String id, String entryId, String status, Instant createdAt, Instant resolvedAt) {}

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ── Entries ─────────────────────────────────────────────────────────────────────────────────────────────

    public void insert(LedgerEntry e) {
        PurchaseRequest r = e.request();
        jdbc.sql("""
                        INSERT INTO ledger_entries (id, agent_id, mandate_id, merchant, category, description,
                          amount_minor, currency, task, reason, source_url, requested_at, outcome, reasons_json,
                          risk_explanation, status, status_detail, paypal_order_id, paypal_capture_id,
                          paypal_refund_id, approve_url, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(e.id(), r.agentId(), r.mandateId(), r.merchant(), r.category(), r.description(),
                        r.amountMinor(), r.currency(), r.task(), r.reason(), r.sourceUrl(), Db.ts(r.requestedAt()),
                        e.decision().outcome().name(), JSON.writeValueAsString(e.decision().reasons()),
                        e.riskExplanation(), e.status().name(), e.statusDetail(), e.paypalOrderId(),
                        e.paypalCaptureId(), e.paypalRefundId(), e.approveUrl(), Db.ts(e.createdAt()),
                        Db.ts(e.updatedAt()))
                .update();
    }

    /** Updates the mutable parts: status and PayPal references. The request and decision never change. */
    public void update(LedgerEntry e) {
        jdbc.sql("""
                        UPDATE ledger_entries SET status = ?, status_detail = ?, paypal_order_id = ?,
                          paypal_capture_id = ?, paypal_refund_id = ?, approve_url = ?, updated_at = ?
                        WHERE id = ?""")
                .params(e.status().name(), truncate(e.statusDetail(), 500), e.paypalOrderId(), e.paypalCaptureId(),
                        e.paypalRefundId(), e.approveUrl(), Db.ts(e.updatedAt()), e.id())
                .update();
    }

    public Optional<LedgerEntry> find(String id) {
        return jdbc.sql(SELECT + "WHERE l.id = ?").param(id).query(LedgerRepository::map).optional();
    }

    public Optional<LedgerEntry> findByOrderId(String orderId) {
        return jdbc.sql(SELECT + "WHERE l.paypal_order_id = ?").param(orderId).query(LedgerRepository::map).optional();
    }

    public Optional<LedgerEntry> findByCaptureId(String captureId) {
        return jdbc.sql(SELECT + "WHERE l.paypal_capture_id = ?").param(captureId).query(LedgerRepository::map).optional();
    }

    /** Newest first. {@code agentId} null means every agent. */
    public List<LedgerEntry> list(String agentId, int limit) {
        if (agentId == null) {
            return jdbc.sql(SELECT + "ORDER BY l.requested_at DESC, l.created_at DESC, l.id DESC LIMIT ?").param(limit)
                    .query(LedgerRepository::map).list();
        }
        return jdbc.sql(SELECT + "WHERE l.agent_id = ? ORDER BY l.requested_at DESC, l.created_at DESC, l.id DESC LIMIT ?")
                .params(agentId, limit).query(LedgerRepository::map).list();
    }

    /** Entries for one agent since a moment, oldest first (risk history, walk reports). */
    public List<LedgerEntry> since(String agentId, Instant from) {
        return jdbc.sql(SELECT + "WHERE l.agent_id = ? AND l.requested_at >= ? ORDER BY l.requested_at, l.created_at, l.id")
                .params(agentId, Db.ts(from)).query(LedgerRepository::map).list();
    }

    /** Money used in the period: approved, in flight, and pending tugs. Same currency only. */
    public long spentSince(String agentId, String currency, Instant from) {
        return jdbc.sql("SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE agent_id = ? AND currency = ? "
                        + "AND requested_at >= ? AND status IN (" + SPEND_STATUSES + ")")
                .params(agentId, currency, Db.ts(from)).query(Long.class).single();
    }

    public int countSince(String agentId, Instant from) {
        return jdbc.sql("SELECT COUNT(*) FROM ledger_entries WHERE agent_id = ? AND requested_at >= ? "
                        + "AND status IN (" + SPEND_STATUSES + ")")
                .params(agentId, Db.ts(from)).query(Integer.class).single();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM ledger_entries").query(Long.class).single();
    }

    // ── Tugs ────────────────────────────────────────────────────────────────────────────────────────────────

    public void insertTug(Tug t) {
        jdbc.sql("INSERT INTO tugs (id, entry_id, status, created_at, resolved_at) VALUES (?, ?, ?, ?, ?)")
                .params(t.id(), t.entryId(), t.status(), Db.ts(t.createdAt()), Db.ts(t.resolvedAt())).update();
    }

    public Optional<Tug> findTug(String id) {
        return jdbc.sql("SELECT * FROM tugs WHERE id = ?").param(id).query(LedgerRepository::mapTug).optional();
    }

    /**
     * Moves a tug out of PENDING. Returns false if it had already been answered, so two clicks (or two browser
     * tabs) can't both approve and pay twice.
     */
    public boolean resolveTug(String id, String status, Instant at) {
        return jdbc.sql("UPDATE tugs SET status = ?, resolved_at = ? WHERE id = ? AND status = 'PENDING'")
                .params(status, Db.ts(at), id).update() == 1;
    }

    public List<Tug> tugs(String status) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM tugs ORDER BY created_at DESC, id DESC").query(LedgerRepository::mapTug).list();
        }
        return jdbc.sql("SELECT * FROM tugs WHERE status = ? ORDER BY created_at DESC, id DESC").param(status)
                .query(LedgerRepository::mapTug).list();
    }

    /** Wipes the ledger (demo reset only). */
    public void deleteAll() {
        jdbc.sql("DELETE FROM tugs").update();
        jdbc.sql("DELETE FROM ledger_entries").update();
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────────────────────────────────

    static LedgerEntry map(ResultSet rs, int row) throws SQLException {
        String id = rs.getString("id");
        var req = new PurchaseRequest(id, rs.getString("agent_id"), rs.getString("mandate_id"), rs.getString("merchant"),
                rs.getString("category"), rs.getString("description"), rs.getLong("amount_minor"),
                rs.getString("currency"), rs.getString("task"), rs.getString("reason"), rs.getString("source_url"),
                Db.instant(rs, "requested_at"));
        List<Reason> reasons = JSON.readValue(rs.getString("reasons_json"), REASONS);
        var decision = new Decision(id, Outcome.valueOf(rs.getString("outcome")), reasons);
        return new LedgerEntry(id, req, decision, rs.getString("risk_explanation"),
                EntryStatus.valueOf(rs.getString("status")), rs.getString("status_detail"),
                rs.getString("paypal_order_id"), rs.getString("paypal_capture_id"), rs.getString("paypal_refund_id"),
                rs.getString("approve_url"), rs.getString("tug_id"), Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"));
    }

    static Tug mapTug(ResultSet rs, int row) throws SQLException {
        return new Tug(rs.getString("id"), rs.getString("entry_id"), rs.getString("status"),
                Db.instant(rs, "created_at"), Db.instant(rs, "resolved_at"));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
