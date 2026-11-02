package com.leash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything configurable lives here and comes from env vars (see .env.example). */
@ConfigurationProperties(prefix = "leash")
public record LeashProperties(PayPal paypal, Llm llm, String corsOrigins) {

    public record PayPal(String baseUrl, String clientId, String clientSecret, String payeeEmail) {
        public boolean configured() {
            return notBlank(clientId) && notBlank(clientSecret);
        }

        /** Hard rule: Leash only ever talks to the PayPal sandbox. */
        public boolean isSandbox() {
            return baseUrl != null && baseUrl.contains("sandbox");
        }
    }

    public record Llm(String baseUrl, String apiKey, String model) {
        public boolean configured() {
            return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
