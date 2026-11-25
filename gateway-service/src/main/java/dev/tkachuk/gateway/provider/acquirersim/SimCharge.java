package dev.tkachuk.gateway.provider.acquirersim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** The parts of an acquirer-sim charge the gateway reads; fields it doesn't know are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
record SimCharge(
        @Nullable String id,
        @Nullable String status,
        @JsonProperty("decline_code") @Nullable String declineCode,
        @JsonProperty("action_url") @Nullable String actionUrl) {
}
