package dev.tkachuk.acquirer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * A charge as the API returns it: "approved" with an auth code, "declined" with a decline code, or
 * "requires_action" with the URL of the 3-D Secure challenge the cardholder must pass first.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Charge(
        String id,
        String reference,
        String status,
        @JsonProperty("decline_code") String declineCode,
        @JsonProperty("auth_code") String authCode,
        @JsonProperty("action_url") String actionUrl,
        long amount,
        String currency,
        @JsonProperty("created_at") Instant createdAt) {

    static final String APPROVED = "approved";
    static final String DECLINED = "declined";
    static final String REQUIRES_ACTION = "requires_action";

    Charge approved(String newAuthCode) {
        return new Charge(id, reference, APPROVED, null, newAuthCode, null, amount, currency, createdAt);
    }

    Charge declined(String newDeclineCode) {
        return new Charge(id, reference, DECLINED, newDeclineCode, null, null, amount, currency, createdAt);
    }

    boolean waitsForChallenge() {
        return REQUIRES_ACTION.equals(status);
    }
}
