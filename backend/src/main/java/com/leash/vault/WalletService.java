package com.leash.vault;

import com.leash.api.NotConfiguredException;
import com.leash.paypal.PayPalClient;
import com.leash.paypal.PayPalException;
import com.leash.vault.WalletRepository.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner's saved PayPal ("agent pays while you're away"). Set up once: Leash asks PayPal for a setup token, the
 * owner approves it in the sandbox, Leash swaps it for a payment token. From then on approved purchases are paid with
 * that token and no popup. Without a saved wallet, purchases fall back to an approve link per order.
 *
 * <p>Saving a wallet doesn't change what an agent may spend: the engine still decides every purchase, and only APPROVE
 * decisions (or tugs the human approved) ever reach {@link #activeVaultId()}.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final PayPalClient paypal;
    private final WalletRepository repo;
    private final Clock clock;

    public WalletService(PayPalClient paypal, WalletRepository repo, Clock clock) {
        this.paypal = paypal;
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * What the dashboard shows.
     *
     * @param status     NONE, PENDING (approve link waiting) or ACTIVE
     * @param approveUrl the link to open while PENDING
     */
    public record View(String status, String hint, String approveUrl, boolean paypalConfigured) {}

    public synchronized View view() {
        Optional<Wallet> w = repo.find();
        String status = w.map(Wallet::status).orElse("NONE");
        return new View(status, w.map(Wallet::hint).orElse(null),
                "PENDING".equals(status) ? w.map(Wallet::approveUrl).orElse(null) : null, paypal.configured());
    }

    /** Starts (or restarts) the one-time setup and returns the link the owner opens in the sandbox. */
    public synchronized View start() {
        if (!paypal.configured()) throw new NotConfiguredException("PayPal isn't configured. Add sandbox credentials to .env.");
        var existing = repo.find();
        if (existing.isPresent() && existing.get().active()) {
            throw new IllegalStateException("PayPal is already saved. Forget it first to save a different account.");
        }
        var token = paypal.createSetupToken("setup-" + UUID.randomUUID());
        if (token.approveUrl() == null) {
            throw new PayPalException("PayPal created setup token " + token.id() + " without an approve link (status "
                    + token.status() + ").");
        }
        repo.save(new Wallet("PENDING", token.id(), token.approveUrl(), null, null, null), clock.instant());
        return view();
    }

    /**
     * Finishes setup after the owner approved: PayPal's return page calls this, and so can the dashboard's
     * "I've approved it" button (in case the redirect doesn't reach us). Safe to call twice.
     */
    public synchronized View complete() {
        Wallet w = repo.find().orElseThrow(() -> new IllegalStateException("Start saving PayPal first."));
        if (w.active()) return view();
        if (w.setupTokenId() == null) throw new IllegalStateException("Start saving PayPal first.");
        PayPalClient.PaymentToken token;
        try {
            token = paypal.createPaymentToken(w.setupTokenId());
        } catch (PayPalException e) {
            log.warn("Saving PayPal failed: {}", e.getMessage());
            throw new IllegalStateException("PayPal didn't save the account yet. Approve it in the sandbox window, then try again. ("
                    + e.getMessage() + ")");
        }
        repo.save(new Wallet("ACTIVE", null, null, token.id(), token.hint(), null), clock.instant());
        return view();
    }

    /** The owner cancelled in PayPal: back to no wallet. */
    public synchronized View cancel() {
        repo.find().filter(w -> !w.active()).ifPresent(w -> repo.delete());
        return view();
    }

    /** Forgets the wallet here and at PayPal. Purchases go back to an approve link each. */
    public synchronized View forget() {
        repo.find().ifPresent(w -> {
            if (w.paymentTokenId() != null && paypal.configured()) {
                try {
                    paypal.deletePaymentToken(w.paymentTokenId());
                } catch (PayPalException e) {
                    // Still forget it locally: Leash will never use it again either way.
                    log.warn("PayPal delete of the saved token failed: {}", e.getMessage());
                }
            }
            repo.delete();
        });
        return view();
    }

    /** The vault ID to charge, if the owner has saved PayPal. */
    public Optional<String> activeVaultId() {
        return repo.find().filter(Wallet::active).map(Wallet::paymentTokenId);
    }
}
