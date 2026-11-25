package dev.tkachuk.gateway.provider;

import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.common.Currency;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A charge for a provider. The reference is the payment's id; the amount is in minor units. returnUrl is the
 * gateway's page the provider sends the customer's browser back to after a 3-D Secure challenge.
 */
public record ChargeRequest(UUID reference, long amount, Currency currency, Card card, @Nullable String description,
        URI returnUrl) {
}
