package com.nfv.paypal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nfv.paypal.client.PayPalHttp;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Payments of the platform to PayPal accounts with the Payouts API. Payouts are asynchronous: sending only
 * creates a batch, its final state is read with {@link #getPayout(String)}, see Docs/paypal.md.
 */
@Component
@RequiredArgsConstructor
public class PayPalPayouts {
    private final PayPalHttp http;

    /**
     * Sends one payout to the account with the given payer id.
     *
     * @param senderBatchId our unique id of the payout. PayPal refuses a second batch with the same id (30 days),
     *                      so a payout is never sent twice
     */
    public PayoutBatch send(String senderBatchId, String receiverPayerId, PayPalAmount amount, String emailSubject,
                            String note) {
        http.requireConfigured();
        var body = Map.of(
                "sender_batch_header", Map.of(
                        "sender_batch_id", senderBatchId,
                        "recipient_type", "PAYPAL_ID",
                        "email_subject", emailSubject),
                "items", List.of(Map.of(
                        "recipient_type", "PAYPAL_ID",
                        "receiver", receiverPayerId,
                        "sender_item_id", senderBatchId,
                        "note", note,
                        "amount", Map.of("currency", amount.currency(), "value", amount.formattedValue()))));
        BatchResponse response = http.post("/v1/payments/payouts", body, BatchResponse.class, null);
        return new PayoutBatch(response.batchHeader().payoutBatchId(), response.batchHeader().batchStatus(), null, null);
    }

    /** Current state of a payout sent with {@link #send}. */
    public PayoutBatch getPayout(String payoutBatchId) {
        http.requireConfigured();
        BatchResponse response = http.get("/v1/payments/payouts/" + payoutBatchId, BatchResponse.class);
        Item item = response.items() == null || response.items().isEmpty() ? null : response.items().getFirst();
        return new PayoutBatch(response.batchHeader().payoutBatchId(), response.batchHeader().batchStatus(),
                item == null ? null : item.transactionStatus(),
                item == null || item.errors() == null ? null : item.errors().name());
    }

    /**
     * @param batchStatus       e.g. {@code PENDING}, {@code PROCESSING}, {@code SUCCESS}, {@code DENIED}
     * @param transactionStatus status of the single item: {@code SUCCESS} is final; {@code FAILED},
     *                          {@code RETURNED}, {@code REFUNDED}, {@code REVERSED}, {@code BLOCKED},
     *                          {@code DENIED} mean the money is back; others are still open
     * @param error             PayPal error name of the item, if any
     */
    public record PayoutBatch(String payoutBatchId, String batchStatus, @Nullable String transactionStatus,
                              @Nullable String error) {
        private static final List<String> RETURNED = List.of("FAILED", "RETURNED", "REFUNDED", "REVERSED", "BLOCKED", "DENIED");

        public boolean isSucceeded() {
            return "SUCCESS".equals(transactionStatus);
        }

        public boolean isReturned() {
            return (transactionStatus != null && RETURNED.contains(transactionStatus))
                    || (transactionStatus == null && "DENIED".equals(batchStatus));
        }
    }

    record BatchResponse(@JsonProperty("batch_header") BatchHeader batchHeader, @Nullable List<Item> items) {
    }

    record BatchHeader(@JsonProperty("payout_batch_id") String payoutBatchId,
                       @JsonProperty("batch_status") String batchStatus) {
    }

    record Item(@JsonProperty("transaction_status") @Nullable String transactionStatus, @Nullable ItemError errors) {
    }

    record ItemError(@Nullable String name, @Nullable String message) {
    }
}
