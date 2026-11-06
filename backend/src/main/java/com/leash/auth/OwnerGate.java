package com.leash.auth;

import com.leash.api.DemoController;
import com.leash.config.DemoProperties;
import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides who may call each API write (T17, Samaun's call on 2026-11-06):
 * <ul>
 *   <li>{@link OwnerOnly}: issuing agent keys, saving/forgetting PayPal, creating or editing leashes (and the
 *       plain-English parser that feeds that), dev endpoints.</li>
 *   <li>{@link DemoScoped}: public for the three demo leashes, owner for the rest. Snap back / re-leash, answering
 *       tugs, capture, Regret, suggestions. Demo data resets hourly, so a visitor can't break it for the next.</li>
 *   <li>Everything else is public: every GET, the demo run/reset, agent purchases (those need an agent key) and
 *       PayPal's webhook (signature-checked).</li>
 * </ul>
 * The rule sits on the controller method, not on a path pattern, so path tricks ({@code ;params}, encodings,
 * double slashes) can't reach a guarded method by a route the gate didn't recognise.
 *
 * <p>In public mode it also rate-limits writes per visitor, demo runs for everyone, and passphrase guesses.
 */
@Component
public class OwnerGate implements HandlerInterceptor {

    public static final String HEADER = "X-Leash-Owner";

    private static final Set<String> DEMO_AGENTS =
            DemoLeashes.all().stream().map(DemoLeashes.Seed::agentId).collect(Collectors.toUnmodifiableSet());

    static final Duration MINUTE = Duration.ofMinutes(1);
    static final Duration HOUR = Duration.ofHours(1);
    static final Duration GUESS_WINDOW = Duration.ofMinutes(10);
    static final int GUESSES_PER_VISITOR = 10;
    static final int GUESSES_PER_HOUR = 100;

    private final DemoProperties demo;
    private final LedgerRepository ledger;
    private final RateLimiter limits;
    private final byte[] passphraseHash;

    public OwnerGate(DemoProperties demo, LedgerRepository ledger, RateLimiter limits) {
        this.demo = demo;
        this.ledger = ledger;
        this.limits = limits;
        this.passphraseHash = demo.ownerPassphrase() == null ? null : sha256(demo.ownerPassphrase());
    }

    public static boolean isDemoAgent(String agentId) {
        return DEMO_AGENTS.contains(agentId);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod hm)) return true; // static files, MCP router: not ours
        String method = req.getMethod();
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        boolean webhook = hm.getBeanType() == com.leash.api.WebhookController.class;

        if (demo.publicMode() && write && !webhook) { // PayPal's webhook is signature-checked and never throttled
            String visitor = "w:" + visitor(req);
            if (!limits.allow(visitor, demo.writesPerMinute(), MINUTE)) {
                return tooMany(res, limits.retryAfterSeconds(visitor, MINUTE),
                        "Slow down a little: the public demo allows " + demo.writesPerMinute() + " actions a minute.");
            }
            if (hm.getBeanType() == DemoController.class && hm.getMethod().getName().equals("run")
                    && !limits.allow("demo-runs", demo.demoRunsPerHour(), HOUR)) {
                return tooMany(res, limits.retryAfterSeconds("demo-runs", HOUR),
                        "The demo has been run a lot this hour. Try again later, or look at the ledger from the last run.");
            }
        }

        if (!demo.gated() || !needsOwner(hm, req)) return true;
        return checkOwner(req, res);
    }

    /** True when this call is owner-only, or touches something other than demo data. */
    boolean needsOwner(HandlerMethod hm, HttpServletRequest req) {
        if (hm.hasMethodAnnotation(OwnerOnly.class) || hm.getBeanType().isAnnotationPresent(OwnerOnly.class)) return true;
        DemoScoped scoped = hm.getMethodAnnotation(DemoScoped.class);
        if (scoped == null) return false;
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) return true; // can't tell whose data this is: treat it as the owner's
        Optional<String> agent = switch (scoped.value()) {
            case AGENT -> Optional.ofNullable(vars.get("agentId"));
            case TUG -> Optional.ofNullable(vars.get("id")).flatMap(ledger::findTug)
                    .map(LedgerRepository.Tug::entryId).flatMap(ledger::find).map(LedgerEntry::agentId);
            case ENTRY -> Optional.ofNullable(vars.get("id")).flatMap(ledger::find).map(LedgerEntry::agentId);
        };
        // An unknown tug or entry passes: the controller answers 404, which says nothing about anyone's data.
        if (agent.isEmpty()) return scoped.value() == DemoScoped.Target.AGENT;
        return !isDemoAgent(agent.get());
    }

    private boolean checkOwner(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (passphraseHash == null) {
            return problem(res, 403, "OWNER_DISABLED", "Owner actions are off",
                    "This is the public demo and no owner passphrase is set, so agent keys, PayPal and leash edits are "
                            + "switched off. The demo leashes still work.");
        }
        String sent = req.getHeader(HEADER);
        if (sent != null && !sent.isBlank()) {
            if (!guessAllowed(req)) {
                return tooMany(res, limits.retryAfterSeconds("g:" + visitor(req), GUESS_WINDOW),
                        "Too many passphrase attempts. Wait a few minutes.");
            }
            if (matches(sent)) return true;
        }
        return problem(res, 401, "OWNER_REQUIRED", "Owner passphrase needed",
                sent == null || sent.isBlank()
                        ? "Only the owner can do this. Unlock owner mode with the passphrase."
                        : "That passphrase isn't right.");
    }

    /**
     * Counts one passphrase guess; false when this visitor or everyone together guessed too often. Counted before
     * comparing, so even a right guess can't be brute-forced past the limit.
     */
    public boolean guessAllowed(HttpServletRequest req) {
        return limits.allow("g:" + visitor(req), GUESSES_PER_VISITOR, GUESS_WINDOW)
                && limits.allow("g:all", GUESSES_PER_HOUR, HOUR);
    }

    public boolean passphraseSet() {
        return passphraseHash != null;
    }

    /** Constant-time compare of digests, so timing says nothing about how much of a guess was right. */
    public boolean matches(String candidate) {
        return passphraseHash != null && candidate != null
                && MessageDigest.isEqual(passphraseHash, sha256(candidate.strip()));
    }

    /**
     * The visitor's address. Behind Render's proxy the socket address is the proxy's, so this takes the first
     * X-Forwarded-For entry. A client can fake that header, which only buys it more per-visitor budget: the
     * all-visitor limits (demo runs, passphrase guesses) still hold.
     */
    static String visitor(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String first = fwd.split(",")[0].strip();
            if (!first.isEmpty()) return first.length() > 64 ? first.substring(0, 64) : first;
        }
        return req.getRemoteAddr();
    }

    private static boolean tooMany(HttpServletResponse res, long retryAfter, String detail) throws IOException {
        res.setHeader("Retry-After", Long.toString(retryAfter));
        return problem(res, 429, "RATE_LIMITED", "Too many requests", detail);
    }

    /** Same shape as {@code ApiErrors}' problem+json. Every string here is a constant from this class. */
    private static boolean problem(HttpServletResponse res, int status, String code, String title, String detail)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status + ",\"code\":\"" + code
                + "\",\"detail\":\"" + detail + "\"}");
        return false;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
