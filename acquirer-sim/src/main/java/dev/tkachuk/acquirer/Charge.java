package dev.tkachuk.acquirer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** A charge as the API returns it: status "approved" with an auth code, or "declined" with a decline code. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record Charge(
        String id,
        String reference,
        String status,
        @JsonProperty("decline_code") String declineCode,
        @JsonProperty("auth_code") String authCode,
        long amount,
        String currency,
        @JsonProperty("created_at") Instant createdAt) {

    static final String APPROVED = "approved";
    static final String DECLINED = "declined";
}
