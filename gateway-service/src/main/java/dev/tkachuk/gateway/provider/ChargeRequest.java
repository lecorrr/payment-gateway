package dev.tkachuk.gateway.provider;

import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.common.Currency;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A charge for a provider. The reference is the payment's id; the amount is in minor units. */
public record ChargeRequest(UUID reference, long amount, Currency currency, Card card, @Nullable String description) {
}
