package dev.tkachuk.gateway.provider;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a connector gets to find a refund again: the refund's id, which went with the refund as its reference; the
 * provider's id for the charge it refunds; and the provider's own id for the refund, when the gateway has one.
 */
public record RefundLookup(UUID reference, String chargeReference, @Nullable String providerReference) {

    public RefundLookup {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(chargeReference, "chargeReference");
    }
}
