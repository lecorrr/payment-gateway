package dev.tkachuk.acquirer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A refund as the API returns it: "succeeded", "failed" with a failure code, or "pending" while it is being worked
 * on. The reference is the caller's id for the refund: one refund per reference.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Refund(
        String id,
        @JsonProperty("charge_id") String chargeId,
        String reference,
        long amount,
        String currency,
        String status,
        @JsonProperty("failure_code") String failureCode,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {

    static final String SUCCEEDED = "succeeded";
    static final String FAILED = "failed";
    static final String PENDING = "pending";

    Refund finished(String newStatus, String newFailureCode, Instant now) {
        return new Refund(id, chargeId, reference, amount, currency, newStatus, newFailureCode, createdAt, now);
    }

    boolean isPending() {
        return PENDING.equals(status);
    }

    boolean isSucceeded() {
        return SUCCEEDED.equals(status);
    }

    /** Whether it counts against what is left of its charge: a failed refund gives its amount back. */
    boolean takesAmount() {
        return !FAILED.equals(status);
    }
}
