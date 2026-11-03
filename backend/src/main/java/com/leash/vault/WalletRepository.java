package com.leash.vault;

import com.leash.store.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** The single saved-wallet row. Holds PayPal token IDs and a masked hint only. */
@Repository
public class WalletRepository {

    static final String OWNER = "owner";

    private final JdbcClient jdbc;

    public WalletRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param setupTokenId   while PENDING
     * @param paymentTokenId once ACTIVE: the vault ID orders are paid with
     */
    public record Wallet(String status, String setupTokenId, String approveUrl, String paymentTokenId, String hint,
                         Instant updatedAt) {
        public boolean active() {
            return "ACTIVE".equals(status) && paymentTokenId != null;
        }
    }

    public Optional<Wallet> find() {
        return jdbc.sql("SELECT * FROM paypal_wallet WHERE id = ?").param(OWNER)
                .query((rs, n) -> new Wallet(rs.getString("status"), rs.getString("setup_token_id"),
                        rs.getString("approve_url"), rs.getString("payment_token_id"), rs.getString("hint"), Db.instant(rs, "updated_at")))
                .optional();
    }

    public void save(Wallet w, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE paypal_wallet SET status = ?, setup_token_id = ?, approve_url = ?, payment_token_id = ?, hint = ?,
                          updated_at = ?
                        WHERE id = ?""")
                .params(w.status(), w.setupTokenId(), w.approveUrl(), w.paymentTokenId(), w.hint(), Db.ts(now), OWNER)
                .update();
        if (updated == 1) return;
        jdbc.sql("""
                        INSERT INTO paypal_wallet (id, status, setup_token_id, approve_url, payment_token_id, hint, created_at,
                          updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(OWNER, w.status(), w.setupTokenId(), w.approveUrl(), w.paymentTokenId(), w.hint(), Db.ts(now),
                        Db.ts(now))
                .update();
    }

    public void delete() {
        jdbc.sql("DELETE FROM paypal_wallet WHERE id = ?").param(OWNER).update();
    }
}
