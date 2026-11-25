package dev.tkachuk.acquirer;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** POST /v1/charges. The reference is the caller's id for the charge: one charge per reference. */
record ChargeRequest(
        @NotBlank @Size(max = 64) String reference,
        @NotNull @Positive Long amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @Valid Card card,
        @Size(max = 140) String description) {

    record Card(
            @NotNull @Pattern(regexp = "[0-9]{12,19}") String number,
            @JsonProperty("exp_month") @NotNull @Min(1) @Max(12) Integer expMonth,
            @JsonProperty("exp_year") @NotNull @Min(2000) @Max(2100) Integer expYear,
            @NotNull @Pattern(regexp = "[0-9]{3,4}") String cvc,
            @Size(max = 100) String holder) {

        @Override
        public String toString() {
            return "Card[***]";
        }
    }
}
