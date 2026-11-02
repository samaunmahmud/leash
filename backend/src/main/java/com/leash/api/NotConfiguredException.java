package com.leash.api;

/** A feature needs credentials that aren't set (PayPal, LLM, webhook). Answered with 503. */
public class NotConfiguredException extends RuntimeException {
    public NotConfiguredException(String message) {
        super(message);
    }
}
