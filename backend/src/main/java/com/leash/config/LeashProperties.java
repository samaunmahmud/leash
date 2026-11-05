package com.leash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

/**
 * Everything configurable lives here and comes from env vars (see .env.example).
 *
 * @param publicUrl    where this backend is reachable from a browser; PayPal sends the payer back here after approval
 * @param appUrl       where the dashboard lives; the PayPal return page links back to it
 * @param devEndpoints enables /api/dev/** (manual PayPal testing). Off in production.
 */
@ConfigurationProperties(prefix = "leash")
public record LeashProperties(PayPal paypal, Llm llm, String corsOrigins, String publicUrl, String appUrl,
                              boolean devEndpoints) {

    public LeashProperties {
        if (paypal == null) paypal = new PayPal(null, null, null, null, null);
        if (llm == null) llm = new Llm(null, null, null, 0);
        if (publicUrl == null || publicUrl.isBlank()) publicUrl = "http://localhost:8080";
        if (appUrl == null || appUrl.isBlank()) appUrl = "http://localhost:5173";
        publicUrl = stripSlash(publicUrl);
        appUrl = stripSlash(appUrl);
    }

    /**
     * @param webhookId the ID PayPal gives the webhook you register in the developer dashboard (T15)
     */
    public record PayPal(String baseUrl, String clientId, String clientSecret, String payeeEmail, String webhookId) {
        public boolean configured() {
            return notBlank(clientId) && notBlank(clientSecret);
        }

        /**
         * Hard rule: Leash only ever talks to the PayPal sandbox. The host must be PayPal's sandbox API, or a local
         * fake (tests). A substring check would let "https://api-m.paypal.com/?sandbox" through, so parse the host.
         */
        public boolean isSandbox() {
            if (baseUrl == null || baseUrl.isBlank()) return true; // falls back to the sandbox default
            try {
                String host = URI.create(baseUrl).getHost();
                if (host == null) return false;
                host = host.toLowerCase(Locale.ROOT);
                return host.endsWith(".sandbox.paypal.com") || host.equals("localhost") || host.equals("127.0.0.1");
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        public boolean webhookConfigured() {
            return notBlank(webhookId);
        }

        /**
         * A startup warning when the payee can't work with Regret, else null. With a payee email on the order the money
         * goes to that account; PayPal lets this app capture for it but only the receiving merchant may refund, so a
         * Personal sandbox payee (…@personal.example.com) makes every refund fail with 403 PERMISSION_DENIED. Seen live
         * on 2026-11-05. Empty = paid into the REST app's own business account, which can refund.
         */
        public String payeeWarning() {
            if (!notBlank(payeeEmail)) return null;
            String e = payeeEmail.trim().toLowerCase(Locale.ROOT);
            if (!e.endsWith("@personal.example.com")) return null;
            return "LEASH_PAYPAL_PAYEE_EMAIL is a Personal sandbox account (" + mask(e) + "). PayPal lets Leash take "
                    + "payments for it but not refund them, so Regret will fail with 403 PERMISSION_DENIED. Leave "
                    + "LEASH_PAYPAL_PAYEE_EMAIL empty to be paid into this app's own sandbox business account.";
        }

        /** "sb-abc123@personal.example.com" → "sb***@personal.example.com": enough to recognise, not the address. */
        static String mask(String email) {
            int at = email.indexOf('@');
            return (at <= 2 ? "" : email.substring(0, 2)) + "***" + (at < 0 ? "" : email.substring(at));
        }
    }

    /**
     * @param maxCallsPerDay  spend guard: after this many LLM calls in a day Leash stops calling and uses heuristics
     * @param reasoningEffort optional OpenAI-style reasoning_effort (none, minimal, low, medium, high), sent only when
     *                        set. Thinking models (Gemini 3, OpenAI o-series) otherwise think long enough to blow the
     *                        12 s timeout; some non-reasoning models reject the parameter, hence opt-in.
     */
    public record Llm(String baseUrl, String apiKey, String model, int maxCallsPerDay, String reasoningEffort) {
        public static final java.util.Set<String> EFFORTS = java.util.Set.of("none", "minimal", "low", "medium", "high");

        /** Spring binds through this one. Without the annotation the second constructor makes binding ambiguous and
         * Spring silently leaves the AI unconfigured (caught by AiWiringTest). */
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Llm {
            reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null
                    : reasoningEffort.strip().toLowerCase(Locale.ROOT);
            if (reasoningEffort != null && !EFFORTS.contains(reasoningEffort)) {
                throw new IllegalArgumentException("LEASH_LLM_REASONING_EFFORT must be one of " + EFFORTS);
            }
        }

        public Llm(String baseUrl, String apiKey, String model, int maxCallsPerDay) {
            this(baseUrl, apiKey, model, maxCallsPerDay, null);
        }

        public boolean configured() {
            return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
