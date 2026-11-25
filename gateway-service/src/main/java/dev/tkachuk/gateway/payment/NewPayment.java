package dev.tkachuk.gateway.payment;

import dev.tkachuk.gateway.card.Card;
import dev.tkachuk.gateway.common.Currency;
import org.jspecify.annotations.Nullable;

/** A merchant's payment request, checked. The amount is in minor units. */
public record NewPayment(String orderId, long amount, Currency currency, @Nullable String description, Card card) {
}
