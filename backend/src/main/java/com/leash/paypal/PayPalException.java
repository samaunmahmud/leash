package com.leash.paypal;

/**
 * A PayPal call failed. The message carries the HTTP status, PayPal's error name and its debug_id (which PayPal
 * support can trace), never the response body: bodies can contain payer details.
 */
public class PayPalException extends RuntimeException {

    private final int status;
    private final String errorName;

    public PayPalException(String message) {
        this(message, 0, null, null);
    }

    public PayPalException(String message, int status, String errorName, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorName = errorName;
    }

    /** HTTP status from PayPal, or 0 if the call never got an answer. */
    public int status() {
        return status;
    }

    /** PayPal's error "name", e.g. UNPROCESSABLE_ENTITY, or null. */
    public String errorName() {
        return errorName;
    }
}
