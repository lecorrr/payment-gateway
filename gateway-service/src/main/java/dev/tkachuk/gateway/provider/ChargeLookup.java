package dev.tkachuk.gateway.provider;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a connector gets to find a charge again: the payment's id, which went with the charge as its reference; the
 * provider's own id for the charge, when the gateway has one; and when the charge was sent.
 */
public record ChargeLookup(UUID reference, @Nullable String providerReference, Instant chargedAt) {

    public ChargeLookup {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(chargedAt, "chargedAt");
    }
}
