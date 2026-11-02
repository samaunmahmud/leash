package com.leash.policy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/** Formatting only. Arithmetic stays in long minor units everywhere else. */
public final class Money {
    private Money() {}

    /** 1840, "GBP" → "£18.40". */
    public static String format(long minor, String currency) {
        try {
            Currency c = Currency.getInstance(currency);
            BigDecimal value = BigDecimal.valueOf(minor, c.getDefaultFractionDigits());
            return c.getSymbol(Locale.UK) + value.toPlainString();
        } catch (IllegalArgumentException | NullPointerException e) {
            return BigDecimal.valueOf(minor, 2).toPlainString() + " " + currency;
        }
    }

    /** 1840, "GBP" → "18.40": the string PayPal's Orders API expects in amount.value. */
    public static String toPayPalValue(long minor, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        return BigDecimal.valueOf(minor, digits).toPlainString();
    }
}
