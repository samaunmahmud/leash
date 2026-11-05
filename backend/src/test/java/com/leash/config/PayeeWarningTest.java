package com.leash.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The startup warning for a payee that would make every Regret refund fail (seen live: 403 PERMISSION_DENIED). */
class PayeeWarningTest {

    static String warning(String payee) {
        return new LeashProperties.PayPal("https://api-m.sandbox.paypal.com", "id", "secret", payee, null).payeeWarning();
    }

    @Test
    void personalSandboxPayeeIsWarnedAboutWithoutPrintingTheAddress() {
        String w = warning("sb-abc123@personal.example.com");
        assertThat(w).contains("Personal sandbox account", "Regret will fail with 403", "Leave LEASH_PAYPAL_PAYEE_EMAIL empty")
                .contains("sb***@personal.example.com")
                .doesNotContain("sb-abc123");
        assertThat(warning("  SB-X@Personal.Example.com ")).isNotNull();
    }

    @Test
    void emptyOrBusinessPayeeIsFine() {
        assertThat(warning(null)).isNull();
        assertThat(warning("")).isNull();
        assertThat(warning("   ")).isNull();
        assertThat(warning("sb-shop@business.example.com")).isNull();
    }

    @Test
    void masksShortAddressesToo() {
        assertThat(LeashProperties.PayPal.mask("ab@personal.example.com")).isEqualTo("***@personal.example.com");
        assertThat(LeashProperties.PayPal.mask("nobody")).isEqualTo("***");
    }
}
