package com.leash.ledger;

import com.leash.policy.Decision;
import com.leash.policy.PurchaseRequest;

import java.time.Instant;

/**
 * One purchase request and everything that happened to it. Holds PayPal IDs but never payer details.
 *
 * @param riskExplanation the AI risk checker's one-sentence explanation, when it ran
 * @param tugId           the tug waiting on (or answered by) the human, if the outcome was TUG
 */
public record LedgerEntry(
        String id,
        PurchaseRequest request,
        Decision decision,
        String riskExplanation,
        EntryStatus status,
        String statusDetail,
        String paypalOrderId,
        String paypalCaptureId,
        String paypalRefundId,
        String approveUrl,
        String tugId,
        Instant createdAt,
        Instant updatedAt) {

    public String agentId() {
        return request.agentId();
    }

    public LedgerEntry withStatus(EntryStatus s, String detail, Instant at) {
        return new LedgerEntry(id, request, decision, riskExplanation, s, detail, paypalOrderId, paypalCaptureId,
                paypalRefundId, approveUrl, tugId, createdAt, at);
    }

    public LedgerEntry withPayPal(String orderId, String captureId, String approve, Instant at) {
        return new LedgerEntry(id, request, decision, riskExplanation, status, statusDetail,
                orderId != null ? orderId : paypalOrderId, captureId != null ? captureId : paypalCaptureId,
                paypalRefundId, approve, tugId, createdAt, at);
    }

    public LedgerEntry withRefund(String refundId, Instant at) {
        return new LedgerEntry(id, request, decision, riskExplanation, status, statusDetail, paypalOrderId,
                paypalCaptureId, refundId, approveUrl, tugId, createdAt, at);
    }
}
