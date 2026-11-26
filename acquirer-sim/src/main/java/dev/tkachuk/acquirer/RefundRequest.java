package dev.tkachuk.acquirer;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** POST /v1/refunds: all or part of a charge, by its id. The reference is the caller's id for the refund. */
record RefundRequest(
        @JsonProperty("charge_id") @NotBlank @Size(max = 64) String chargeId,
        @NotBlank @Size(max = 64) String reference,
        @NotNull @Positive Long amount) {
}
