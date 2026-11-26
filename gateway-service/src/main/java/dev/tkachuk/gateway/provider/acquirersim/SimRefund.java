package dev.tkachuk.gateway.provider.acquirersim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** The parts of an acquirer-sim refund the gateway reads; fields it doesn't know are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
record SimRefund(
        @Nullable String id,
        @Nullable String status,
        @JsonProperty("failure_code") @Nullable String failureCode) {
}
