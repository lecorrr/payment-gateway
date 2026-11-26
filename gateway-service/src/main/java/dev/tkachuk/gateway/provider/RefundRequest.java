package dev.tkachuk.gateway.provider;

import dev.tkachuk.gateway.common.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * A refund for the provider that charged the payment. The reference is the refund's id and goes to the provider with
 * the refund, so one whose answer was lost can still be found; chargeReference is the provider's own id for the
 * charge (the payment's providerReference). The amount is in minor units, all or part of what was charged.
 */
public record RefundRequest(UUID reference, UUID paymentReference, String chargeReference, long amount,
        Currency currency) {

    public RefundRequest {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(paymentReference, "paymentReference");
        Objects.requireNonNull(chargeReference, "chargeReference");
        Objects.requireNonNull(currency, "currency");
        if (amount <= 0) {
            throw new IllegalArgumentException("A refund's amount must be positive, got " + amount);
        }
    }
}
